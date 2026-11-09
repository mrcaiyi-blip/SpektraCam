// LutCam mapped DIR transport. GPL-3.0-only. Model arithmetic remains on CPU/GPU.
#pragma once
#include "gpu/vulkan_compute.h"
namespace spk::gpu {
struct SixthPassDiagnostics {
    bool attempted=false,engaged=false;int self_check=0;double self_error=0;
    double prepare_ms=0,upload_ms=0,diffusion_ms=0,readback_ms=0,finish_ms=0;
    uint64_t avoided_host_bytes=0;const char* reason="not_requested";
};
void set_sixth_pass_enabled(bool);
bool sixth_pass_enabled();
void reset_sixth_pass_diagnostics();
SixthPassDiagnostics sixth_pass_diagnostics();
// Callbacks run synchronously under the GPU mutex. They may only do CPU work.
// Finish is called once, only after finite successful readback. Any callback
// exception propagates to the renderer (never retry with partially-written data).
using MappedPrepare=void(*)(void*,float*);
using MappedFinish=void(*)(void*,const float*);
bool mapped_dir_diffusion(const HalationScatterRequest&,MappedPrepare,MappedFinish,void*);
}
