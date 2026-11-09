#include "spektrafilm_adapter.h"
#include "film_output.h"
#include "film_output_transform.h"
#include <limits>
#include "profiles/json_min.h"
#include "runtime/stage_timer.h"
#include "pipeline/film_linear_file.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
int always_cancel(void*) { return 1; }
int cancel_mid_render(void* counter) { return ++*static_cast<int*>(counter)>=5; }

int main(int argc, char** argv) {
    try {
        require(argc==2,"Expected bundled asset directory");
        const float sampleRgb[]={-.1f,.5f,1.1f,1.f/255.f,.25f,.75f};
        uint8_t packed[16];std::fill(std::begin(packed),std::end(packed),42);
        lutcam::film::to_rgba(sampleRgb,1,2,packed,8);
        const uint8_t expectedPacked[]={0,128,255,255,42,42,42,42,1,64,191,255,42,42,42,42};
        require(std::equal(std::begin(packed),std::end(packed),std::begin(expectedPacked)),"Bitmap channels, quantization, stride or alpha changed");
        const float invalidRgb[]={0,std::numeric_limits<float>::quiet_NaN(),1};
        bool badPixel=false;try{lutcam::film::to_rgba(invalidRgb,1,1,packed,4);}catch(const std::exception&){badPixel=true;}
        require(badPixel,"Nonfinite film Bitmap accepted");
        // Independently enumerated labels for a cropped 3x2 patch, all rotations.
        std::vector<float> numbered(5*4*3);for(int p=0;p<20;p++)for(int c=0;c<3;c++)numbered[p*3+c]=(p+1)/255.f;
        const int expectedRotations[4][6]={{7,8,9,12,13,14},{12,7,13,8,14,9},{14,13,12,9,8,7},{9,14,8,13,7,12}};
        for(int angle=0;angle<4;angle++) {
            int ow=angle%2?2:3,oh=angle%2?3:2;size_t stride=ow*4+7;
            std::vector<uint8_t> actual(stride*oh,42);
            lutcam::film::to_rgba_transform(numbered.data(),5,4,actual.data(),stride,1,1,3,2,angle*90);
            for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){
                for(int c=0;c<3;c++)require(actual[y*stride+x*4+c]==expectedRotations[angle][y*ow+x],"Fused crop/rotation mapping changed");
                require(actual[y*stride+x*4+3]==255,"Transformed alpha changed");
            }
            for(int y=0;y<oh;y++)for(size_t x=ow*4;x<stride;x++)require(actual[y*stride+x]==42,"Transformed row padding overwritten");
        }
        bool invalidTransform=false;try{lutcam::film::to_rgba_transform(numbered.data(),5,4,packed,8,4,0,2,2,90);}catch(const std::exception&){invalidTransform=true;}
        require(invalidTransform,"Invalid crop accepted");
        setenv("SPK_PARALLEL_MIN_CHUNK","1",1);setenv("SPK_NUM_THREADS","4",1);
        std::vector<float> odd(129*95*3);for(size_t i=0;i<odd.size();i++)odd[i]=float(i%1024)/1023.f;
        std::vector<uint8_t> serial(129*95*4),tiled(serial.size());
        lutcam::film::to_rgba(odd.data(),129,95,serial.data(),129*4);
        lutcam::film::to_rgba_transform(odd.data(),129,95,tiled.data(),129*4,0,0,129,95,0);
        require(serial==tiled,"Parallel fused output changed legacy RGB8 bytes");
        unsetenv("SPK_PARALLEL_MIN_CHUNK");unsetenv("SPK_NUM_THREADS");
        // Independent colour-science reference values, not a round-trip through our matrix.
        const uint8_t primaries[]={255,0,0,255,0,255,0,255,0,0,255,255,128,128,128,255};
        float linear[12]{};
        lutcam::film::display_to_prophoto(primaries,4,linear);
        const float expected[]={.52938847f,.09830509f,.01685106f,.33023122f,.87348390f,.11769684f,
                                .14062717f,.02814606f,.86567327f,.21591379f,.21584648f,.21590824f};
        for (int i=0;i<12;i++) require(std::abs(linear[i]-expected[i])<2e-6f,"sRGB transfer, matrix or channel order mismatch");
        lutcam::film::display_to_prophoto(primaries+12,1,linear,1);
        require(linear[1]>.25f && linear[1]<.27f,"BT709 transfer was treated as sRGB");
        const std::string linearPath="film-linear-test.lcrf";
        const std::vector<float> hdr{0.00012f,0.184f,1.5f,4.25f,0.66f,2.125f};
        raw_isp::film_io::write(linearPath,2,1,hdr);
        int fw=0,fh=0;
        auto loaded=raw_isp::film_io::read(linearPath,&fw,&fh);
        require(fw==2 && fh==1 && loaded==hdr,"Linear RAW RGB lost float precision or HDR headroom");
        {std::ofstream broken(linearPath,std::ios::binary|std::ios::app);broken.put('x');}
        bool rejected=false;try{raw_isp::film_io::read(linearPath,&fw,&fh);}catch(const std::exception&){rejected=true;}
        require(rejected,"Oversized linear RAW payload accepted");
        std::remove(linearPath.c_str());

        spk_engine* engine=nullptr;
        require(spk_engine_create(argv[1],&engine)==SPK_OK,"Engine creation failed");
        constexpr int w=96,h=72;
        std::vector<uint8_t> input(w*h*4,255),output(input.size(),42);
        // Grey ramp, saturated patches, dark edges and a localized white light source.
        for (int y=0;y<h;y++) for(int x=0;x<w;x++) {
            size_t i=(y*w+x)*4;
            input[i]=static_cast<uint8_t>(x*255/(w-1));
            input[i+1]=static_cast<uint8_t>(y*255/(h-1));
            input[i+2]=static_cast<uint8_t>((x+y)*255/(w+h-2));
            if (x>w/3 && x<w/2 && y>h/3 && y<h/2) input[i]=input[i+1]=input[i+2]=255;
        }
        auto catalogFile=std::ifstream(std::string(argv[1])+"/catalog.json");
        const std::string json((std::istreambuf_iterator<char>(catalogFile)),{});
        auto catalog=spk::json::parse(json);
        int count=0,positiveCount=0;
        for (const auto& item:catalog->at("stocks").object) {
            const std::string id=item.first;
            const std::string group=item.second->at("group").str;
            if (group=="print_paper" || group=="print_film") continue;
            std::ifstream profileFile(std::string(argv[1])+"/profiles/"+id+".json");
            const std::string profileJson((std::istreambuf_iterator<char>(profileFile)),{});
            auto profile=spk::json::parse(profileJson);
            const auto& info=profile->at("info");
            bool positive=info.at("type").str=="positive";
            std::string paper=positive?"":info.at("target_print").str;
            lutcam::film::Look look{id.c_str(),paper.c_str(),false};
            auto start=std::chrono::steady_clock::now();
            auto status=lutcam::film::render_rgba(engine,input.data(),output.data(),w,h,look);
            if(status!=SPK_OK) std::fprintf(stderr,"%s: %s / %s\n",id.c_str(),spk_status_str(status),spk_last_error_message());
            require(status==SPK_OK,"Full model render failed");
            const auto& stages=spk::stage_timing_snapshot();
            require(stages.grain_ran && stages.stages_ms[spk::STG_GRAIN]>0,"Grain was skipped");
            require(stages.stages_ms[spk::STG_HALATION]>0,"Halation/scatter was skipped");
            require(stages.stages_ms[spk::STG_DIR_COUPLERS]>0,"DIR couplers were skipped");
            if (!positive) require(stages.stages_ms[spk::STG_PRINT_EXPOSE]>0,"Print medium was skipped");
            auto range=std::minmax_element(output.begin(),output.end());
            require(*range.first<200,"Output appears constant/white");
            for(size_t i=3;i<output.size();i+=4)require(output[i]==255,"RGBA order/alpha mismatch");
            char timings[2048]{};spk_stage_timings_json(timings,sizeof(timings));
            auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
            std::printf("PASS %-28s print=%-36s %lld ms %s\n",id.c_str(),paper.c_str(),static_cast<long long>(ms),timings);
            count++;if(positive)positiveCount++;
        }
        require(count==20 && positiveCount==4,"Capture stocks or reversal classification changed");
        lutcam::film::Look portra{"kodak_portra_400","kodak_portra_endura",false};
        std::fill(output.begin(),output.end(),42);
        require(lutcam::film::render_rgba(engine,input.data(),output.data(),w,h,portra,always_cancel,nullptr)==SPK_ERR_CANCELLED,
                "Superseded frame was not cancelled");
        require(std::all_of(output.begin(),output.end(),[](uint8_t v){return v==42;}),"Cancelled frame published partial pixels");
        int checks=0;
        require(lutcam::film::render_rgba(engine,input.data(),output.data(),w,h,portra,cancel_mid_render,&checks)==SPK_ERR_CANCELLED,
                "Active model did not honor cancellation");
        require(std::all_of(output.begin(),output.end(),[](uint8_t v){return v==42;}),"Interrupted render published partial pixels");
        portra.encoding=999;
        require(lutcam::film::render_rgba(engine,input.data(),output.data(),w,h,portra)==SPK_ERR_BAD_ARGS,"Unknown input encoding accepted");
        std::vector<float> rawLinear(w*h*3);
        lutcam::film::display_to_prophoto(input.data(),w*h,rawLinear.data());
        for(float& value:rawLinear)value*=4.0f;
        spk_image rawResult{};
        require(lutcam::film::render_linear(engine,rawLinear.data(),w,h,portra,true,&rawResult)==SPK_OK,
                "Scene-linear HDR RAW input failed");
        require(rawResult.width==w && rawResult.height==h,"RAW model changed input resolution");
        for(int i=0;i<w*h*3;i++)require(std::isfinite(rawResult.data[i]),"Nonfinite RAW film result");
        spk_image_free(&rawResult);
        spk_engine_destroy(engine);
        std::puts("PASS: input color conversion, 20 full-effect stocks, 4 direct reversal scans, cancellation and encoding validation");
        return 0;
    } catch(const std::exception& error) {std::fprintf(stderr,"FAIL: %s\n",error.what());return 1;}
}
