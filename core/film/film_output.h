#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#if defined(__APPLE__) && defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace lutcam::film {
inline uint8_t encoded_byte(float value) {
    if(!std::isfinite(value))throw std::runtime_error("Nonfinite RAW film output");
    return static_cast<uint8_t>(std::lround(std::clamp(value,0.f,1.f)*255.f));
}
// Android RGBA_8888 native byte order; opaque alpha makes premultiplication inert.
// Same quantization as the legacy RGB8 file route, with independent row strides.
inline void to_rgba(const float* rgb,int width,int height,uint8_t* rgba,size_t stride) {
    if(!rgb || !rgba || width<=0 || height<=0 || static_cast<uint64_t>(width)*height>32ull*1024*1024
            || stride<static_cast<size_t>(width)*4)
        throw std::runtime_error("Invalid film Bitmap output");
    for(int y=0;y<height;y++) {
        auto* row=rgba+static_cast<size_t>(y)*stride;
        const float* source=rgb+static_cast<size_t>(y)*width*3;
        int x=0;
#if defined(__APPLE__) && defined(__aarch64__)
        // Interleave eight pixels at a time. FCVTAU rounds the nonnegative
        // scaled value exactly like lround, including ties at half a byte.
        const auto zero=vdupq_n_f32(0), one=vdupq_n_f32(1);
        const auto scale=vdupq_n_f32(255), largest=vdupq_n_f32(0x1.fffffep127f);
        const auto encode=[&](float32x4_t v) {
            return vmovn_u32(vcvtaq_u32_f32(vmulq_f32(vminq_f32(vmaxq_f32(v,zero),one),scale)));
        };
        uint32x4_t finite=vdupq_n_u32(UINT32_MAX);
        for(;x+8<=width;x+=8) {
            const auto a=vld3q_f32(source+size_t(x)*3), b=vld3q_f32(source+size_t(x+4)*3);
            uint8x8x4_t pixels;
            for(int c=0;c<3;c++) {
                finite=vandq_u32(finite,vcleq_f32(vabsq_f32(a.val[c]),largest));
                finite=vandq_u32(finite,vcleq_f32(vabsq_f32(b.val[c]),largest));
                pixels.val[c]=vmovn_u16(vcombine_u16(encode(a.val[c]),encode(b.val[c])));
            }
            pixels.val[3]=vdup_n_u8(255);
            vst4_u8(row+size_t(x)*4,pixels);
        }
        if(vminvq_u32(finite)!=UINT32_MAX)throw std::runtime_error("Nonfinite RAW film output");
#endif
        for(;x<width;x++) {
            row[x*4]=encoded_byte(source[x*3]);row[x*4+1]=encoded_byte(source[x*3+1]);
            row[x*4+2]=encoded_byte(source[x*3+2]);row[x*4+3]=255;
        }
    }
}
}
