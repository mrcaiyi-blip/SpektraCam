// LutCam resident grain chain, GPL-3.0-only. Original source files retained.
#pragma once
#include <cstdint>
#include "model/grain.h"
namespace spk::gpu {
struct GrainChainDiagnostics {
    bool attempted=false,engaged=false;
    int self_check=0;
    double self_check_max_error=0,upload_ms=0,compute_wait_ms=0,readback_ms=0;
    uint32_t dispatches=0,submissions=0,out_of_branch=0;
    uint64_t bytes_up=0,bytes_down=0,avoided_layer_host_bytes=0;
    const char* reason="not_requested";
};
void set_grain_chain_enabled(bool enabled);
void reset_grain_chain_diagnostics();
GrainChainDiagnostics grain_chain_diagnostics();
// Original layer and particle shaders, resident intermediates, final subtraction
// and reflected FIR. On refusal caller output is untouched, including aliasing.
bool grain_chain(const float* density,int width,int height,const float* axis,
                 const float* layers,int points,bool positive,double pixel_size_um,
                 const GrainParams& grain,float* output);
}
