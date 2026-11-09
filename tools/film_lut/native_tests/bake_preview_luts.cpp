// Reproducible preview assets from the unmodified upstream pointwise bake API.
#include "spektra.h"
#include "profiles/json_min.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <memory>
#include <iostream>
#include <iterator>
#include <random>
#include <sstream>
#include <stdexcept>
#include <vector>

static float decode(float x) { return x<=.04045f?x/12.92f:std::pow((x+.055f)/1.055f,2.4f); }
static void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
static void u32(std::ofstream& f,uint32_t v){for(int b=0;b<4;b++)f.put(static_cast<char>((v>>(8*b))&255));}
static std::string read(const std::string& file){std::ifstream f(file);return {(std::istreambuf_iterator<char>(f)),{}};}
struct Preview {
    std::vector<float> lut;
    double rmse, p99, maximum;
    bool accepted() const { return rmse < .004 && p99 < .015; }
};

static Preview bake(spk_engine* engine, const std::string& id, const std::string& paper, int n) {
    const size_t count = size_t(n) * n * n;
    const bool positive = paper.empty();
    spk_params p{};spk_default_params(&p);p.film_profile=id.c_str();p.print_profile=paper.c_str();p.scan_film=positive;
    p.input_cctf_decoding=0;p.output_color_space=SPK_CS_SRGB;p.output_cctf_encoding=1;p.disable_buffer_memos=1;
    // Avoid the sizing query: upstream executes the full bake for sizing too.
    std::vector<char> cube(count*64+8192);size_t needed=0;
    require(spk_bake_cube_lut(engine,&p,n,1,cube.data(),cube.size(),&needed)==SPK_OK,"Bake failed");
    std::vector<float> lut(count*3);std::istringstream lines(cube.data());std::string line;size_t index=0;
    while(std::getline(lines,line)){
        std::istringstream row(line);float rgb[3];if(!(row>>rgb[0]>>rgb[1]>>rgb[2]))continue;
        require(index<count,"Oversized cube");int b=index%n,g=(index/n)%n,r=index/(n*n);
        size_t target=static_cast<size_t>((b*n+g)*n+r)*3; // upstream blue-fast -> GLES red-fast
        for(int c=0;c<3;c++){require(std::isfinite(rgb[c]),"Nonfinite baked node");lut[target+c]=std::round(std::clamp(rgb[c],0.f,1.f)*65535.f)/65535.f;}
        index++;
    }
    require(index==count,"Missing cube nodes");
    // Compare off-grid asymmetric samples with an independent direct engine render.
    // Match the documented pointwise bake contract; do not compare to spatial/grain output.
    p.auto_exposure=0;p.grain_active=0;p.halation_active=0;p.glare_active=0;p.print_glare_active=0;
    p.camera_diffusion_active=0;p.enlarger_diffusion_active=0;p.lens_blur_um=0;p.dir_diffusion_size_um=0;
    p.scanner_lens_blur=0;p.scanner_unsharp[0]=p.scanner_unsharp[1]=0;
    constexpr int samples=4096;std::mt19937 random(20261020);std::uniform_real_distribution<float> distribution(0,1);
    std::vector<float> shaped(samples*3),linear(samples*3);
    for(int i=0;i<samples;i++)for(int c=0;c<3;c++){
        float v=i<256?float(i)/255.f:distribution(random);shaped[i*3+c]=v;linear[i*3+c]=decode(v);
    }
    spk_image input{linear.data(),samples,1,SPK_CS_PROPHOTO},output{};
    require(spk_simulate(engine,&input,&p,&output)==SPK_OK,"Reference render failed");
    double square=0;std::vector<double> errors;errors.reserve(samples*3);
    for(int i=0;i<samples;i++){
        int lo[3];float t[3];for(int c=0;c<3;c++){float q=shaped[i*3+c]*(n-1);lo[c]=std::min(n-2,int(q));t[c]=q-lo[c];}
        for(int c=0;c<3;c++){
            double value=0;for(int b=0;b<2;b++)for(int g=0;g<2;g++)for(int r=0;r<2;r++){
                size_t node=static_cast<size_t>(((lo[2]+b)*n+lo[1]+g)*n+lo[0]+r)*3+c;
                value+=lut[node]*(r?t[0]:1-t[0])*(g?t[1]:1-t[1])*(b?t[2]:1-t[2]);
            }
            double e=std::abs(value-std::clamp(output.data[i*3+c],0.f,1.f));square+=e*e;errors.push_back(e);
        }
    }
    spk_image_free(&output);std::sort(errors.begin(),errors.end());double rmse=std::sqrt(square/errors.size()),p99=errors[errors.size()*99/100];
    return {std::move(lut), rmse, p99, errors.back()};
}

static void write(const std::string& path, const Preview& preview, int n) {
    std::ofstream file(path, std::ios::binary);
    u32(file, 0x54554c50); u32(file, 1); u32(file, n); u32(file, 1);
    for (float value : preview.lut) {
        uint16_t q = static_cast<uint16_t>(std::lround(value * 65535.f));
        file.put(q & 255);
        file.put(q >> 8);
    }
    file.close();
    require(bool(file), "LUT write failed");
}

int main(int argc, char** argv) try {
    require(argc == 3 || (argc == 4 && std::string(argv[3]) == "--ios-print-variants"),
            "Usage: bake_preview_luts ASSET_DIR OUTPUT_DIR [--ios-print-variants]");
    const bool variants = argc == 4;
    const std::string root = argv[1], dest = argv[2];
    std::filesystem::create_directories(dest);
    spk_engine* engine = nullptr;
    require(spk_engine_create(root.c_str(), &engine) == SPK_OK, "Engine creation failed");
    std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> owner(engine, spk_engine_destroy);
    auto catalog = spk::json::parse(read(root + "/catalog.json"));
    const auto& stocks = catalog->at("stocks").object;
    std::ofstream manifest(dest + "/manifest.json");
    manifest << "{\"schema\":1,\"engineCommit\":\"15fb120996f03fc1e58a9a9d819e335f65d7a092\"";
    if (!variants) manifest << ",\"size\":65";
    manifest << ",\"input\":\"linear ProPhoto D50, shaped per channel with sRGB OETF\",\"output\":\"encoded sRGB\","
                "\"order\":\"red-fast\",\"autoExposure\":false,\"spatialEffects\":false,\"looks\":[";
    int total = 0;
    for (const auto& item : stocks) {
        const auto& group = item.second->at("group").str;
        if (group == "print_paper" || group == "print_film") continue;
        const auto& id = item.first;
        auto profile = spk::json::parse(read(root + "/profiles/" + id + ".json"));
        const auto& info = profile->at("info");
        const std::string preferred = info.at("type").str == "positive" ? "" : info.at("target_print").str;
        std::vector<std::string> papers;
        if (!variants) {
            papers.push_back(preferred);
        } else if (!preferred.empty()) {
            const char* printGroup = group == "motion_picture_negative" ? "print_film" : "print_paper";
            for (const auto& candidate : stocks) {
                if (candidate.first != preferred && candidate.second->at("group").str == printGroup) {
                    papers.push_back(candidate.first);
                }
            }
        }
        for (const auto& paper : papers) {
            int size = variants ? 33 : 65;
            auto preview = bake(engine, id, paper, size);
            for (int larger : {49, 65}) {
                if (!variants || preview.accepted()) break;
                size = larger;
                preview = bake(engine, id, paper, size);
            }
            const std::string key = variants ? id + "--" + paper : id;
            if (!preview.accepted()) throw std::runtime_error("Preview LUT interpolation error for " + key);
            write(dest + "/" + key + ".plut", preview, size);
            if (total++) manifest << ",";
            manifest << "{\"film\":\"" << id << "\",\"print\":\"" << paper << "\"";
            if (variants) manifest << ",\"size\":" << size;
            manifest << ",\"samples\":4096,\"rmse\":" << preview.rmse
                     << ",\"p99\":" << preview.p99 << ",\"max\":" << preview.maximum << "}";
            std::cout << key << " size=" << size << " rmse=" << preview.rmse
                      << " p99=" << preview.p99 << " max=" << preview.maximum << std::endl;
        }
    }
    require(total == (variants ? 60 : 20), "Incomplete preview catalog");
    manifest << "]}\n";
    manifest.close();
    require(bool(manifest), "Manifest write failed");
    return 0;
} catch (const std::exception& error) {
    std::cerr << error.what() << std::endl;
    return 1;
}
