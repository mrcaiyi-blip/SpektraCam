// Included in the spatial DIR function, after the existing small f64 curve setup.
// Conversion to float is at the same seam as the old GPU upload. CPU oracle intact.
if(params.allow_gpu_diffusion && gpu::third_pass_enabled() && gpu::available()) {
    std::vector<float> correction_f32(static_cast<size_t>(npix)*3);
    parallel_for(0,npix,[&](int lo,int hi){for(int p=lo;p<hi;p++){
        double silver[3];for(int k=0;k<3;k++){
            double s=static_cast<double>(density_cmy[p*3+k]);
            if(positive_film)s=dmax_cmy[k]-s;if(shift!=0)s+=shift*s*s;silver[k]=s;
        }
        for(int c=0;c<3;c++){double corr=0;for(int k=0;k<3;k++)corr+=silver[k]*M[k*3+c];correction_f32[p*3+c]=static_cast<float>(corr);}
    }});
    gpu::HalationScatterRequest request{};request.raw_rgb_f32=correction_f32.data();
    request.width=w;request.height=h;request.pixel_size_um=pixel_size_um;
    request.scatter_amount=1;request.scatter_spatial_scale=1;request.halation_n_bounces=0;
    for(int c=0;c<3;c++){
        request.scatter_core_um[c]=params.diffusion_size_um;
        request.scatter_tail_um[c]=params.diffusion_tail_um;
        request.scatter_tail_weight[c]=params.diffusion_tail_weight;
    }
    std::vector<float> diffused_f32(static_cast<size_t>(npix)*3);
    gpu::HalationScatterDiagnostics gd{};
    if(gpu::halation_scatter_f32(request,diffused_f32.data(),&gd) && gd.engaged) {
        stage_timing_note_gpu_dir_diffusion(gd.attempted,gd.engaged,gd.reason,gd.bands,gd.dispatches,gd.upload_ms,gd.gpu_ms,gd.readback_ms);
        std::vector<double> axis[3],curve[3];UniformAxis<double> ua[3];
        for(int c=0;c<3;c++){
            axis[c].resize(n);curve[c].resize(n);
            for(int k=0;k<n;k++){axis[c][k]=le[k]/static_cast<double>(gamma_factor[c]);curve[c][k]=dc0[k*3+c];}
            ua[c]=detect_uniform_axis(axis[c].data(),n,1);
        }
        parallel_for(0,npix,[&](int lo,int hi){for(int p=lo;p<hi;p++)for(int c=0;c<3;c++){
            double x=static_cast<double>(log_raw[p*3+c])-static_cast<double>(diffused_f32[p*3+c]);
            out[p*3+c]=static_cast<float>(fast_interp_channel(x,axis[c].data(),curve[c].data(),n,ua[c]));
        }});
        gpu::note_dir_f32();return;
    }
}
