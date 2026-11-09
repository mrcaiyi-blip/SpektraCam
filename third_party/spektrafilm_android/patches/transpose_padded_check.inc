// Runs only with no resident frame, under GPU mutex. Same diffusion graph,
// old transpose layout versus padded transpose layout. Includes narrow axes and partial edge tiles.
void qualify_padded_transpose(Ctx& c) {
    ScopedStageTimingSuppression suppress;lutcam_padded_checking=true;bool ok=true;
    const bool policy=seventh_pass_enabled();const auto previous=lutcam_seventh_diagnostics;
    try {
        struct Case {int w,h;double sigma;};
        for(const auto test:{Case{17,13,2.999},Case{1,19,2.999},Case{23,1,2.999},Case{3,5,2.999},
                             Case{271,257,.65},Case{271,257,2.999},Case{271,257,12},Case{271,257,256}}) {
            const int n=test.w*test.h*3;std::vector<float> input(n),before(n),after(n);
            for(int i=0;i<n;i++)input[i]=.001f+float((i*7919)%4093)/1024.f;
            HalationScatterRequest r{};r.raw_rgb_f32=input.data();r.width=test.w;r.height=test.h;r.pixel_size_um=1;
            r.scatter_amount=1;r.scatter_spatial_scale=1;r.halation_n_bounces=0;
            for(int ch=0;ch<3;ch++){r.scatter_core_um[ch]=ch==0?test.sigma:(ch==1?1.49:12.);r.scatter_tail_weight[ch]=0;}
            HalationScatterDiagnostics a{},b{};
            set_seventh_pass_enabled(false);bool old=halation_scatter_locked(c,r,nullptr,a,before.data());
            set_seventh_pass_enabled(true);bool newer=halation_scatter_locked(c,r,nullptr,b,after.data());
            if(!old || !newer || !a.engaged || !b.engaged || !c.halation.paddedPipe){ok=false;break;}
            for(int i=0;i<n;i++){
                double error=std::fabs(double(before[i])-after[i]);lutcam_padded_error=std::max(lutcam_padded_error,error);
                if(!std::isfinite(error) || error!=0){ok=false;break;}
            }
            if(!ok)break;
        }
    }catch(...){ok=false;}
    set_seventh_pass_enabled(policy);lutcam_seventh_diagnostics=previous;
    lutcam_padded_checking=false;lutcam_padded_check=ok?1:-1;
}
