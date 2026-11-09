#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

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
        for(int x=0;x<width;x++) {
            row[x*4]=encoded_byte(source[x*3]);row[x*4+1]=encoded_byte(source[x*3+1]);
            row[x*4+2]=encoded_byte(source[x*3+2]);row[x*4+3]=255;
        }
    }
}
}
