// Full-film RGB -> cropped/rotated RGBA in one tiled packing pass.
#pragma once
#include "film_output.h"
#include "kernels/parallel.h"
namespace lutcam::film {
inline void to_rgba_transform(const float* rgb,int width,int height,uint8_t* rgba,size_t stride,
                             int left,int top,int cw,int ch,int rotation) {
    if(!rgb || !rgba || width<=0 || height<=0 || uint64_t(width)*height>32ull*1024*1024 ||
       left<0 || top<0 || cw<=0 || ch<=0 || cw>width-left || ch>height-top ||
       (rotation!=0 && rotation!=90 && rotation!=180 && rotation!=270))
        throw std::runtime_error("Invalid film output transform");
    const int ow=rotation%180?ch:cw;
    if(stride<size_t(ow)*4)throw std::runtime_error("Invalid transformed Bitmap stride");
    constexpr int tile=32;const int nx=(cw+tile-1)/tile,ny=(ch+tile-1)/tile;
    // Each source tile maps to a disjoint destination tile; retain cache locality
    // for both source triples and transposed output. No interpolation/resize.
    spk::parallel_for(0,nx*ny,[&](int lo,int hi){for(int t=lo;t<hi;t++) {
        const int x0=(t%nx)*tile,y0=(t/nx)*tile;
        for(int y=y0;y<std::min(y0+tile,ch);y++)for(int x=x0;x<std::min(x0+tile,cw);x++){
            int dx=x,dy=y;
            if(rotation==90){dx=ch-1-y;dy=x;}
            else if(rotation==180){dx=cw-1-x;dy=ch-1-y;}
            else if(rotation==270){dx=y;dy=cw-1-x;}
            const float* src=rgb+(size_t(top+y)*width+left+x)*3;
            auto* dst=rgba+size_t(dy)*stride+size_t(dx)*4;
            for(int c=0;c<3;c++)dst[c]=encoded_byte(src[c]);dst[3]=255;
        }
    }});
}
}
