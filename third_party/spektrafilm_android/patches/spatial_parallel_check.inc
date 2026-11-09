// Called before outer request setup, under the GPU mutex. Tests the exact old
// pipeline against new lane mapping: odd geometry, mixed channels and wide IIR.
void qualify_spatial_parallel(Ctx& c) {
    lutcam_spatial_checking=true;bool ok=true;
    const bool policy=third_pass_enabled();
    try {
        constexpr int w=127,h=93;std::vector<double> input(w*h*3),before(w*h*3),after(w*h*3);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int ch=0;ch<3;ch++)
            input[(y*w+x)*3+ch]=.001+((x>w/2)?1.0:.2)+((x>50 && x<57 && y>40 && y<48)?8:0)+ch*.07;
        for(double sigma: {3.,12.,63.,256.}) {
            HalationScatterRequest r{};r.raw_rgb=input.data();r.width=w;r.height=h;r.pixel_size_um=1;
            r.scatter_amount=1;r.scatter_spatial_scale=1;r.halation_n_bounces=0;
            for(int ch=0;ch<3;ch++){r.scatter_core_um[ch]=ch==0?.65:sigma;r.scatter_tail_weight[ch]=0;}
            HalationScatterDiagnostics a{},b{};
            set_third_pass_enabled(false);bool old=halation_scatter_locked(c,r,before.data(),a);
            set_third_pass_enabled(true);bool newer=halation_scatter_locked(c,r,after.data(),b);
            if(!old || !newer || !c.halation.fastPipe || !a.engaged || !b.engaged){ok=false;break;}
            for(size_t i=0;i<before.size();i++){
                double error=std::fabs(after[i]-before[i])/std::max(1.0,std::fabs(before[i]));
                lutcam_spatial_error=std::max(lutcam_spatial_error,error);
                if(!std::isfinite(error) || error>1e-6){ok=false;break;}
            }
            if(!ok)break;
        }
    }catch(...){ok=false;}
    set_third_pass_enabled(policy);lutcam_third_diagnostics.spatial_parallel=false;lutcam_spatial_checking=false;lutcam_spatial_check=ok?1:-1;
}
