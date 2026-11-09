// Included inside spk::gpu. GPL-3.0-only.
bool halation_scatter_f32(const HalationScatterRequest& request,float* output,HalationScatterDiagnostics* diagnostics) {
    HalationScatterDiagnostics d{};
    if(!output || !request.raw_rgb_f32 || request.raw_rgb){d.reason="invalid-f32-request";if(diagnostics)*diagnostics=d;return false;}
    bool spatial=request.mixture_count>0 || request.halation_n_bounces>0;
    for(int ch=0;ch<3;ch++)spatial=spatial || request.scatter_core_um[ch]>0 || request.scatter_tail_um[ch]>0;
    if(!spatial){d.reason="inactive-f32-request";if(diagnostics)*diagnostics=d;return false;}
    bool ok=false;
    try {
        std::lock_guard<std::mutex> lock(gpu_mutex());Ctx& c=ctx();
        ok=halation_scatter_locked(c,request,nullptr,d,output);
        if(!ok && (std::strcmp(d.reason,"pipeline-failed")==0 || std::strcmp(d.reason,"allocation-failed")==0 ||
                   std::strcmp(d.reason,"dispatch-failed")==0))c.destroyHalation();
    }catch(...){d.reason="f32-exception";}
    if(diagnostics)*diagnostics=d;return ok;
}
