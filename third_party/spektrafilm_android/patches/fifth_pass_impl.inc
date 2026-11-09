// Included inside spk::gpu; locked partial residency, original shader arithmetic.
namespace {
int lutcam_expose_check=0;double lutcam_expose_error=0;
bool run_expose_chain(Ctx& c,const double* f64,const float* f32,double gain,int w,int h,
                     const double* tc,int edge,double exp,const HalationScatterRequest& request,
                     float* output,FifthPassDiagnostics& d) {
    if(c.frame.open){d.reason="frame-already-open";return false;}
    if(seventh_pass_enabled() && !lutcam_padded_checking && lutcam_padded_check==0)qualify_padded_transpose(c);
    struct Close {Ctx& c;~Close(){c.frame.open=false;}} close{c};
    auto exposeTimer=std::make_unique<ScopedStage>(STG_FILMING_EXPOSE);
    const size_t n=size_t(w)*h*3;const auto start=std::chrono::steady_clock::now();
    if(!ensure_frame(c,w,h)){c.destroyFrame();d.reason="allocation";return false;}
    c.frame.front=0;c.frame.width=w;c.frame.height=h;
    std::atomic<bool> finite{true};auto* stage=static_cast<float*>(c.frame.staging.mapped);
    parallel_for(0,int(n),[&](int lo,int hi){for(int i=lo;i<hi;i++){
        const float v=f32?f32[i]:static_cast<float>(f64[i]);
        if(!std::isfinite(v))finite.store(false,std::memory_order_relaxed);stage[i]=v;
    }});
    if(!finite){d.reason="nonfinite-input";return false;}
    if(!c.frameCopy(true,n*sizeof(float))){c.destroyFrame();d.reason="upload";return false;}
    c.frame.open=true;d.upload_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    FilmingStageRun expose;expose.mode=kFilmModeExpose;expose.npix=w*h;expose.width=w;expose.height=h;
    expose.tc_table=tc;expose.tc_count=size_t(edge)*edge*3;expose.L=edge;expose.n=2;
    expose.src_gain=static_cast<float>(gain);expose.exp_mult=static_cast<float>(exp);
    FilmingStageDiagnostics fd{};
    auto now=std::chrono::steady_clock::now();
    if(!run_filming_stage_locked(c,expose,&fd)){d.reason="expose";return false;}
    d.expose_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    exposeTimer.reset();
    auto r=request;r.raw_rgb=nullptr;r.raw_rgb_f32=nullptr;HalationScatterDiagnostics hd{};
    now=std::chrono::steady_clock::now();
    { ScopedStage halationTimer(STG_HALATION);
      if(!halation_scatter_locked(c,r,nullptr,hd) || !hd.engaged){
          d.reason=hd.reason;
          if(std::strcmp(hd.reason,"pipeline-failed")==0 || std::strcmp(hd.reason,"allocation-failed")==0 ||
             std::strcmp(hd.reason,"dispatch-failed")==0)c.destroyHalation();
          return false;
      } }
    d.halation_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    stage_timing_note_gpu_halation(hd.attempted,hd.engaged,hd.reason,hd.bands,hd.dispatches,hd.upload_ms,hd.gpu_ms,hd.readback_ms);
    FilmingStageRun log;log.mode=kFilmModeLog10;log.npix=int(n);log.npix_is_components=true;
    log.width=w;log.height=h;log.n=2;log.L=2;
    now=std::chrono::steady_clock::now();
    if(!run_filming_stage_locked(c,log,&fd)){d.reason="log";return false;}
    d.log_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    now=std::chrono::steady_clock::now();
    if(!c.frameCopy(false,n*sizeof(float))){c.destroyFrame();d.reason="readback";return false;}
    auto* result=static_cast<const float*>(c.frame.staging.mapped);
    parallel_for(0,int(n),[&](int lo,int hi){for(int i=lo;i<hi;i++)if(!std::isfinite(result[i]))finite.store(false,std::memory_order_relaxed);});
    if(!finite){d.reason="nonfinite-output";return false;}
    parallel_for(0,int(n),[&](int lo,int hi){std::memcpy(output+lo,result+lo,size_t(hi-lo)*sizeof(float));});
    d.readback_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    d.engaged=true;d.reason="none";return true;
}
// The old three stages are the oracle, including every f32 transport seam.
void qualify_expose_chain(Ctx& c,const double* tc,int edge,const HalationScatterRequest& request) {
    ScopedStageTimingSuppression suppress;
    bool ok=true;
    try {
        constexpr int w=127,h=93,n=w*h*3;std::vector<float> input(n),baseline(n),newer(n);
        std::vector<double> raw(n),spatial(n);
        for(int i=0;i<n;i++)input[i]=.001f+float((i*7919)%4093)/1024.f;
        FilmingStageRun ex;ex.mode=kFilmModeExpose;ex.npix=w*h;ex.width=w;ex.height=h;
        ex.tc_table=tc;ex.tc_count=size_t(edge)*edge*3;ex.L=edge;ex.n=2;ex.src_f32=input.data();
        ex.dst_f64=raw.data();ex.src_gain=.713f;ex.exp_mult=1.17f;
        FilmingStageDiagnostics fd{};ok=run_filming_stage_locked(c,ex,&fd);
        auto r=request;r.width=w;r.height=h;r.raw_rgb=raw.data();r.raw_rgb_f32=nullptr;
        HalationScatterDiagnostics hd{};
        if(ok)ok=halation_scatter_locked(c,r,spatial.data(),hd) && hd.engaged;
        FilmingStageRun log;log.mode=kFilmModeLog10;log.npix=n;log.npix_is_components=true;
        log.width=w;log.height=h;log.n=2;log.L=2;log.src_f64=spatial.data();log.dst_f32=baseline.data();
        if(ok)ok=run_filming_stage_locked(c,log,&fd);
        FifthPassDiagnostics d{};if(ok)ok=run_expose_chain(c,nullptr,input.data(),.713f,w,h,tc,edge,1.17f,r,newer.data(),d);
        if(ok)for(int i=0;i<n;i++){
            const double e=std::fabs(double(baseline[i])-newer[i]);lutcam_expose_error=std::max(lutcam_expose_error,e);
            if(!std::isfinite(e) || e>1e-6){ok=false;break;}
        }
    }catch(...){ok=false;}
    c.frame.open=false;lutcam_expose_check=ok?1:-1;
}
}
bool expose_halation_chain(const double* f64,const float* f32,double gain,int w,int h,
                           const double* tc,int edge,double exp,const HalationScatterRequest& request,float* output) {
    auto& d=lutcam_fifth_diagnostics;d.attempted=true;
    if(!fifth_pass_enabled() || !output || (f64==nullptr)==(f32==nullptr) || !tc || edge<2 || w<=0 || h<=0 ||
       uint64_t(w)*h>uint64_t(INT32_MAX)/3 || !std::isfinite(gain) || !std::isfinite(exp) ||
       request.width!=w || request.height!=h || !(request.pixel_size_um>0)) {d.reason="invalid-request";return false;}
    const double scalars[]={request.scatter_amount,request.scatter_spatial_scale,request.halation_amount,request.halation_spatial_scale,request.halation_bounce_decay};
    for(double value:scalars)if(!std::isfinite(value)){d.reason="invalid-parameters";return false;}
    if(request.mixture_count!=0 || request.halation_n_bounces>int(kHalMaxBounces)){d.reason="unsupported-parameters";return false;}
    for(int ch=0;ch<3;ch++) {
        const double values[]={request.scatter_core_um[ch],request.scatter_tail_um[ch],request.scatter_tail_weight[ch],request.halation_strength[ch],request.halation_first_sigma_um[ch]};
        for(double value:values)if(!std::isfinite(value)){d.reason="invalid-parameters";return false;}
        if(request.scatter_tail_weight[ch]<0 || request.scatter_tail_weight[ch]>1){d.reason="tail-weight-out-of-range";return false;}
    }
    bool scatter=false,a=false,sigma=false;
    for(int ch=0;ch<3;ch++) {scatter=scatter || request.scatter_core_um[ch]>0 || request.scatter_tail_um[ch]>0;a=a || request.halation_strength[ch]*request.halation_amount>0;sigma=sigma || request.halation_first_sigma_um[ch]*request.halation_spatial_scale>0;}
    if(!(request.scatter_amount>0 && scatter && request.scatter_spatial_scale>0) && !(request.halation_n_bounces>=1 && a && sigma)){d.reason="inactive-request";return false;}
    try {
        std::lock_guard<std::mutex> lock(gpu_mutex());Ctx& c=ctx();
        if(!c.ok || c.frame.open){d.reason="unavailable-or-busy";return false;}
        if(lutcam_expose_check==0)qualify_expose_chain(c,tc,edge,request);
        d.self_check=lutcam_expose_check;d.self_error=lutcam_expose_error;
        if(lutcam_expose_check!=1){d.reason="self-check";return false;}
        return run_expose_chain(c,f64,f32,gain,w,h,tc,edge,exp,request,output,d);
    }catch(...){d.reason="exception";return false;}
}
