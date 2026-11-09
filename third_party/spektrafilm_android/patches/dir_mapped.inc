// CPU f64 arithmetic and final interpolation are identical to dir_f32.inc.
// Only the full-size correction/readback storage and transfers change.
if(params.allow_gpu_diffusion && gpu::third_pass_enabled() && gpu::sixth_pass_enabled() && gpu::available()) {
    std::vector<double> axis[3],curve[3];UniformAxis<double> ua[3];
    for(int c=0;c<3;c++){
        axis[c].resize(n);curve[c].resize(n);
        for(int k=0;k<n;k++){axis[c][k]=le[k]/static_cast<double>(gamma_factor[c]);curve[c][k]=dc0[k*3+c];}
        ua[c]=detect_uniform_axis(axis[c].data(),n,1);
    }
    auto prepare=[&](float* correction){
        parallel_for(0,npix,[&](int lo,int hi){for(int p=lo;p<hi;p++){
            double silver[3];for(int k=0;k<3;k++){
                double s=static_cast<double>(density_cmy[p*3+k]);
                if(positive_film)s=dmax_cmy[k]-s;if(shift!=0)s+=shift*s*s;silver[k]=s;
            }
            for(int c=0;c<3;c++){double corr=0;for(int k=0;k<3;k++)corr+=silver[k]*M[k*3+c];correction[p*3+c]=static_cast<float>(corr);}
        }});
    };
    auto finish=[&](const float* diffused){
        parallel_for(0,npix,[&](int lo,int hi){for(int p=lo;p<hi;p++)for(int c=0;c<3;c++){
            double x=static_cast<double>(log_raw[p*3+c])-static_cast<double>(diffused[p*3+c]);
            out[p*3+c]=static_cast<float>(fast_interp_channel(x,axis[c].data(),curve[c].data(),n,ua[c]));
        }});
    };
    struct Callbacks {decltype(prepare)& fill;decltype(finish)& consume;} callbacks{prepare,finish};
    gpu::HalationScatterRequest request{};request.width=w;request.height=h;request.pixel_size_um=pixel_size_um;
    request.scatter_amount=1;request.scatter_spatial_scale=1;request.halation_n_bounces=0;
    for(int c=0;c<3;c++){
        request.scatter_core_um[c]=params.diffusion_size_um;request.scatter_tail_um[c]=params.diffusion_tail_um;
        request.scatter_tail_weight[c]=params.diffusion_tail_weight;
    }
    if(gpu::mapped_dir_diffusion(request,
        [](void* raw,float* p){static_cast<Callbacks*>(raw)->fill(p);},
        [](void* raw,const float* p){static_cast<Callbacks*>(raw)->consume(p);},&callbacks)) {
        gpu::note_dir_f32();return;
    }
}
