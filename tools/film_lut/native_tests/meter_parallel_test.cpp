#include "runtime/stages/autoexposure.h"
#include "scan_tail.h"
#include <vector>
#include <cstring>
#include <cstdlib>
#include <stdexcept>
#include <iostream>
#include <cmath>
int main(){
    try{
        for(auto shape:{std::pair<int,int>{1,1031},{1031,1},{777,513},{4096,3072}}){
            int w=shape.first,h=shape.second;std::vector<float> src(size_t(w)*h*3);
            for(size_t i=0;i<src.size();i++)src[i]=float((i*7919)%4093)/701.f-.03f;
            spk::gpu::set_fourth_pass_enabled(false);
            double before=spk::measure_auto_exposure_ev_f32(src.data(),w,h,spk::AeColorSpace::kProPhotoRGB,false,spk::AeMethod::kCenterWeighted,true);
            for(const char* threads:{"1","4"}){
                setenv("SPK_NUM_THREADS",threads,1);spk::gpu::set_fourth_pass_enabled(true);
                double after=spk::measure_auto_exposure_ev_f32(src.data(),w,h,spk::AeColorSpace::kProPhotoRGB,false,spk::AeMethod::kCenterWeighted,true);
                if(std::memcmp(&before,&after,sizeof(double)))throw std::runtime_error("f32 metering EV changed");
            }
            if(w<1000 && h<1000){
                std::vector<double> in(src.begin(),src.end()),a,b;int aw,ah,bw,bh;
                spk::gpu::set_fourth_pass_enabled(false);spk::small_preview(in.data(),w,h,256,&a,&aw,&ah);
                spk::gpu::set_fourth_pass_enabled(true);spk::small_preview(in.data(),w,h,256,&b,&bw,&bh);
                if(aw!=bw || ah!=bh || a.size()!=b.size() || std::memcmp(a.data(),b.data(),a.size()*sizeof(double)))throw std::runtime_error("f64 preview samples changed");
            }
        }
        unsetenv("SPK_NUM_THREADS");spk::gpu::set_fourth_pass_enabled(true);
        std::cout<<"PASS: exact metering EV at 1/4 workers, narrow/odd/12MP, exact f64 preview samples\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
