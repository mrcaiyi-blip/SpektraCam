#include "spektrafilm_adapter.h"
#include "scan_tail.h"
#include "grain_chain.h"
#include "third_pass.h"
#include "fifth_pass.h"
#include "sixth_pass.h"
#include "seventh_pass.h"
#include "runtime/stages/autoexposure.h"
#include "kernels/parallel.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <limits>
#include <stdexcept>
#include <vector>

namespace lutcam::film {

bool valid_exposure_method(const char* method) {
    spk::AeMethod parsed{};
    return spk::ae_method_from_string(method, &parsed);
}

void apply_frozen_auto_exposure(float* rgb, size_t components, float exposureEv) {
    const double gain=std::exp2(double(exposureEv));
    if(!rgb || !std::isfinite(exposureEv) || !std::isfinite(gain) || gain<=0 ||
            components>size_t(std::numeric_limits<int>::max()))
        throw std::invalid_argument("Invalid frozen film auto exposure");
    spk::parallel_for(0,int(components),[&](int begin,int end) {
        for(int i=begin;i<end;++i) {
            const double value=double(rgb[i])*gain;
            if(!std::isfinite(value) || std::abs(value)>std::numeric_limits<float>::max())
                throw std::invalid_argument("Nonfinite exposed film input");
            rgb[i]=float(value);
        }
    });
}

void display_to_prophoto(const uint8_t* rgba, size_t pixels, float* rgb, int encoding) {
    // colour-science 0.4.7 -> ProPhoto D50, Bradford adaptation.
    // sRGB/BT709, Display P3, BT2020, SMPTE C (525), BT470 625, Adobe RGB.
    constexpr double matrices[][9] = {
        {0.529388470712,0.330231217730,0.140627167763,0.098305088514,0.873483901238,0.028146056090,0.016851063808,0.117696843742,0.865673268566},
        {0.631789972010,0.213973374738,0.154415788538,0.083208045136,0.885839904841,0.030911268990,-0.001273083688,0.050769026950,0.950778366361},
        {0.835323078764,0.048885367727,0.115970688795,0.054040655545,0.928890431907,0.017028131516,-0.002342681415,0.036342119421,0.966274871617},
        {0.503000179499,0.344870794895,0.152308160891,0.107898533819,0.848380836728,0.043679848421,0.016547261438,0.110729949501,0.872997098685},
        {0.552678603969,0.308560011659,0.138940519658,0.102700697482,0.869445123533,0.027813397953,0.017623418032,0.127157800160,0.855493091432},
        {0.740241399266,0.113309068159,0.146636385710,0.137545901501,0.833045651158,0.029350745475,0.023602860563,0.073804677633,0.902868593450}
    };
    const double* matrix=matrices[encoding >> 4];
    double decode[256];
    for (int i=0;i<256;++i) {
        const double c=static_cast<double>(i)/255.0;
        switch (encoding & 15) {
            case 1: decode[i]=c<0.081?c/4.5:std::pow((c+0.099)/1.099,1.0/0.45); break;
            case 2: decode[i]=c; break;
            case 3: decode[i]=std::pow(c,2.2); break;
            case 4: decode[i]=std::pow(c,2.6); break;
            case 5: decode[i]=std::pow(c,2.8); break;
            default: decode[i]=c<=0.04045?c/12.92:std::pow((c+0.055)/1.055,2.4); break;
        }
    }
    for (size_t i=0;i<pixels;++i) {
        const double r=decode[rgba[4*i]],g=decode[rgba[4*i+1]],b=decode[rgba[4*i+2]];
        for (int c=0;c<3;++c)
            rgb[3*i+c]=static_cast<float>(matrix[c*3]*r+matrix[c*3+1]*g+matrix[c*3+2]*b);
    }
}

spk_status render_linear(spk_engine* engine, float* prophoto, int width, int height,
                         const Look& look, bool autoExposure, spk_image* output,
                         spk_cancel_check cancelled, void* user, float fixedExposureEv) {
    if (!engine || !prophoto || !output || !look.film || width<=0 || height<=0 ||
            static_cast<uint64_t>(width)*height>32ull*1024*1024) return SPK_ERR_BAD_ARGS;
    if (!valid_exposure_method(look.exposureMethod)) return SPK_ERR_BAD_ARGS;
    spk::gpu::reset_scan_tail_diagnostics();
    spk::gpu::reset_grain_chain_diagnostics();
    spk::gpu::reset_third_pass_diagnostics();
    spk::gpu::reset_fifth_pass_diagnostics();
    spk::gpu::reset_sixth_pass_diagnostics();
    spk::gpu::reset_seventh_pass_diagnostics();
    // Preserve the caller's scene-linear frame and apply a frozen AE at the
    // same point as the engine's ordinary preprocess AE. Using manual EV here
    // would trigger compute_midgray_exposure_factor's print compensation.
    std::vector<float> exposed;
    if(std::isinf(fixedExposureEv))return SPK_ERR_BAD_ARGS;
    if(std::isfinite(fixedExposureEv) && fixedExposureEv!=0) {
        exposed.assign(prophoto,prophoto+size_t(width)*height*3);
        apply_frozen_auto_exposure(exposed.data(),exposed.size(),fixedExposureEv);
    }
    spk_image source{exposed.empty()?prophoto:exposed.data(),width,height,SPK_CS_PROPHOTO};
    spk_params params{};
    spk_default_params(&params);
    params.film_profile = look.film;
    params.print_profile = look.paper;
    params.scan_film = !look.paper || !look.paper[0];
    // Preview is already rendered by camera AE. RAW gets the model's single
    // exposure normalization, without the ISP display gain/tone-map stages.
    params.auto_exposure = autoExposure && !std::isfinite(fixedExposureEv) ? 1 : 0;
    params.auto_exposure_method = look.exposureMethod;
    params.input_cctf_decoding = 0;
    params.output_color_space = SPK_CS_SRGB;
    params.output_cctf_encoding = 1;
    params.disable_buffer_memos = 1;
    params.gpu_export = look.gpu ? 1 : 0;
    // Use the full simulation entry, NOT simulate_preview (which forces LUT approximations).
    // All default grain, halation/scatter, DIR diffusion, printing and glare stay enabled.
    return spk_simulate_cancellable(engine,&source,&params,output,cancelled,user);
}

spk_status render_rgba(spk_engine* engine, const uint8_t* input, uint8_t* output,
                       int width, int height, const Look& look,
                       spk_cancel_check cancelled, void* user) {
    if (!engine || !input || !output || width <= 0 || height <= 0 || !look.film ||
            look.encoding<0 || (look.encoding>>4)>5 || (look.encoding&15)>5)
        return SPK_ERR_BAD_ARGS;
    if (cancelled && cancelled(user)) return SPK_ERR_CANCELLED;
    const size_t pixels = static_cast<size_t>(width) * height;
    if (pixels > 32u * 1024u * 1024u) return SPK_ERR_BAD_ARGS;
    std::vector<float> linear(pixels * 3);
    display_to_prophoto(input, pixels, linear.data(),look.encoding);
    spk_image result{};
    const auto free_image = [](spk_image* image) { spk_image_free(image); };
    std::unique_ptr<spk_image, decltype(free_image)> guard(&result, free_image);
    const auto status=render_linear(engine,linear.data(),width,height,look,false,&result,cancelled,user);
    if (status!=SPK_OK) return status;
    if (!result.data || result.width!=width || result.height!=height) return SPK_ERR_INTERNAL;
    // Reject a failed numeric render, rather than saving NaNs as black pixels.
    for (size_t i = 0; i < pixels * 3; ++i)
        if (!std::isfinite(result.data[i])) return SPK_ERR_INTERNAL;
    if (cancelled && cancelled(user)) return SPK_ERR_CANCELLED;
    for (size_t i = 0; i < pixels; ++i) {
        for (int c = 0; c < 3; ++c)
            output[i*4+c] = static_cast<uint8_t>(std::lround(std::clamp(result.data[i*3+c], 0.0f, 1.0f) * 255.0f));
        output[i*4+3] = 255;
    }
    return SPK_OK;
}

} // namespace lutcam::film
