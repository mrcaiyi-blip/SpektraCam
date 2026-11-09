// Included inside spk::gpu after the old locked diffusion helper. GPLv3.
namespace {
int lutcam_mapped_check=0;double lutcam_mapped_error=0;
bool mapped_workspace_ready(Ctx& c,uint64_t bytes) {
    return c.frame.cpool && c.frame.cmd && c.frame.fence && c.frame.staging.mapped &&
        c.frame.staging.cap>=bytes && c.frame.plane[0].buf && c.frame.plane[0].cap>=bytes &&
        c.frame.plane[1].buf && c.frame.plane[1].cap>=bytes;
}
bool run_mapped_dir(Ctx& c,const HalationScatterRequest& request,MappedPrepare prepare,
                    MappedFinish finish,void* context,SixthPassDiagnostics& d) {
    const uint64_t n=uint64_t(request.width)*request.height*3,bytes=n*sizeof(float);
    if(c.frame.open || !mapped_workspace_ready(c,bytes)){d.reason="workspace-not-ready";return false;}
    if(seventh_pass_enabled() && !lutcam_padded_checking && lutcam_padded_check==0)qualify_padded_transpose(c);
    struct Close {Ctx& c;~Close(){c.frame.open=false;}} close{c};
    c.frame.front=0;c.frame.width=request.width;c.frame.height=request.height;
    auto* stage=static_cast<float*>(c.frame.staging.mapped);
    auto now=std::chrono::steady_clock::now();prepare(context,stage);
    std::atomic<bool> finite{true};
    parallel_for(0,int(n),[&](int lo,int hi){for(int i=lo;i<hi;i++)if(!std::isfinite(stage[i]))finite.store(false,std::memory_order_relaxed);});
    d.prepare_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    if(!finite){d.reason="nonfinite-input";return false;}
    now=std::chrono::steady_clock::now();
    if(!c.frameCopy(true,bytes)){c.destroyFrame();d.reason="upload";return false;}
    c.frame.open=true;
    d.upload_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    HalationScatterDiagnostics hd{};auto r=request;r.raw_rgb=nullptr;r.raw_rgb_f32=nullptr;
    now=std::chrono::steady_clock::now();
    if(!halation_scatter_locked(c,r,nullptr,hd) || !hd.engaged) {
        d.reason=hd.reason;
        if(std::strcmp(hd.reason,"pipeline-failed")==0 || std::strcmp(hd.reason,"allocation-failed")==0 ||
           std::strcmp(hd.reason,"dispatch-failed")==0)c.destroyHalation();
        return false;
    }
    d.diffusion_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    stage_timing_note_gpu_dir_diffusion(hd.attempted,hd.engaged,hd.reason,hd.bands,hd.dispatches,hd.upload_ms,hd.gpu_ms,hd.readback_ms);
    now=std::chrono::steady_clock::now();
    if(!c.frameCopy(false,bytes)){c.destroyFrame();d.reason="readback";return false;}
    parallel_for(0,int(n),[&](int lo,int hi){for(int i=lo;i<hi;i++)if(!std::isfinite(stage[i]))finite.store(false,std::memory_order_relaxed);});
    d.readback_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    if(!finite){d.reason="nonfinite-output";return false;}
    now=std::chrono::steady_clock::now();finish(context,stage);
    d.finish_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-now).count();
    d.engaged=true;d.avoided_host_bytes=bytes*2;d.reason="none";return true;
}
void qualify_mapped_dir(Ctx& c) {
    ScopedStageTimingSuppression suppress;bool ok=true;
    try {
        constexpr int w=127,h=93,n=w*h*3;
        std::vector<float> input(n),baseline(n),after(n);
        for(int i=0;i<n;i++)input[i]=.001f+float((i*7919)%4093)/1024.f;
        struct Copy {const float* input;float* output;int n;} copy{input.data(),after.data(),n};
        auto prep=[](void* raw,float* p){auto& x=*static_cast<Copy*>(raw);std::memcpy(p,x.input,size_t(x.n)*sizeof(float));};
        auto end=[](void* raw,const float* p){auto& x=*static_cast<Copy*>(raw);std::memcpy(x.output,p,size_t(x.n)*sizeof(float));};
        for(double sigma: {.65,3.,12.,63.,256.}) {
            HalationScatterRequest r{};r.raw_rgb_f32=input.data();r.width=w;r.height=h;r.pixel_size_um=1;
            r.scatter_amount=1;r.scatter_spatial_scale=1;r.halation_n_bounces=0;
            for(int ch=0;ch<3;ch++){r.scatter_core_um[ch]=ch==0?.65:sigma;r.scatter_tail_weight[ch]=0;}
            HalationScatterDiagnostics old{};SixthPassDiagnostics d{};
            if(!halation_scatter_locked(c,r,nullptr,old,baseline.data()) || !old.engaged ||
               !run_mapped_dir(c,r,prep,end,&copy,d)){ok=false;break;}
            for(int i=0;i<n;i++){
                double e=std::fabs(double(baseline[i])-after[i]);lutcam_mapped_error=std::max(lutcam_mapped_error,e);
                if(!std::isfinite(e) || e!=0){ok=false;break;}
            }
            if(!ok)break;
        }
    }catch(...){ok=false;}
    c.frame.open=false;lutcam_mapped_check=ok?1:-1;
}
}
bool mapped_dir_diffusion(const HalationScatterRequest& request,MappedPrepare prepare,MappedFinish finish,void* context) {
    auto& d=lutcam_sixth_diagnostics;d.attempted=true;
    if(!sixth_pass_enabled() || !prepare || !finish || request.width<=0 || request.height<=0 ||
       uint64_t(request.width)*request.height>uint64_t(INT32_MAX)/3 || !(request.pixel_size_um>0) ||
       request.mixture_count!=0 || request.halation_n_bounces!=0 || request.raw_rgb || request.raw_rgb_f32) {
        d.reason="invalid-request";return false;
    }
    bool active=false;
    if(!std::isfinite(request.scatter_amount) || !std::isfinite(request.scatter_spatial_scale) ||
       !(request.scatter_amount>0) || !(request.scatter_spatial_scale>0)){d.reason="inactive-request";return false;}
    for(int ch=0;ch<3;ch++) {
        if(!std::isfinite(request.scatter_core_um[ch]) || !std::isfinite(request.scatter_tail_um[ch]) ||
           !std::isfinite(request.scatter_tail_weight[ch]) || request.scatter_tail_weight[ch]<0 ||
           request.scatter_tail_weight[ch]>1){d.reason="invalid-parameters";return false;}
        active=active || request.scatter_core_um[ch]>0 || request.scatter_tail_um[ch]>0;
    }
    if(!active){d.reason="inactive-request";return false;}
    std::lock_guard<std::mutex> lock(gpu_mutex());Ctx& c=ctx();
    if(!c.ok || c.frame.open || !mapped_workspace_ready(c,uint64_t(request.width)*request.height*3*sizeof(float))) {
        d.reason="workspace-not-ready";return false;
    }
    if(lutcam_mapped_check==0)qualify_mapped_dir(c);
    d.self_check=lutcam_mapped_check;d.self_error=lutcam_mapped_error;
    if(lutcam_mapped_check!=1){d.reason="self-check";return false;}
    return run_mapped_dir(c,request,prepare,finish,context,d);
}
