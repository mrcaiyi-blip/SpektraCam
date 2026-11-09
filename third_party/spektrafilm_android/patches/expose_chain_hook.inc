// All active operations in this segment must be represented. Other creative
// settings and positive-film BW scaling retain the complete original route.
if(gpu::fifth_pass_enabled() && gpu_expose_ok && params.allow_gpu_halation &&
   params.halation.active && !(params.halation.boost_ev>0) &&
   !params.diffusion_filter.active && !(params.lens_blur_um>0) && params.bw_exposure_correction==1.) {
    const auto& p=params.halation;gpu::HalationScatterRequest r{};
    r.width=width;r.height=height;r.pixel_size_um=params.pixel_size_um;
    r.scatter_amount=p.scatter_amount;r.scatter_spatial_scale=p.scatter_spatial_scale;
    r.halation_amount=p.halation_amount;r.halation_spatial_scale=p.halation_spatial_scale;
    r.halation_n_bounces=p.halation_n_bounces;r.halation_bounce_decay=p.halation_bounce_decay;
    r.halation_renormalize=p.halation_renormalize;
    for(int c=0;c<3;c++){
        r.scatter_core_um[c]=p.scatter_core_um[c];r.scatter_tail_um[c]=p.scatter_tail_um[c];
        r.scatter_tail_weight[c]=p.scatter_tail_weight[c];r.halation_strength[c]=p.halation_strength[c];
        r.halation_first_sigma_um[c]=p.halation_first_sigma_um[c];
    }
    if(gpu::expose_halation_chain(gpu_rgb_f64,gpu_rgb_f32,gpu_gain,width,height,
                                 tc_lut.data.data(),L,exp_mult,r,log_raw_out)) {
        g_gpu_halation_frames.fetch_add(1,std::memory_order_relaxed);return;
    }
}
