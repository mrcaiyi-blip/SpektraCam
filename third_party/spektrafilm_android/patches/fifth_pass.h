// LutCam partial resident film chain. GPL-3.0-only.
#pragma once
#include "gpu/vulkan_compute.h"
namespace spk::gpu {
struct FifthPassDiagnostics {bool attempted=false,engaged=false;int self_check=0;double self_error=0,upload_ms=0,expose_ms=0,halation_ms=0,log_ms=0,readback_ms=0;const char* reason="not_requested";};
void set_fifth_pass_enabled(bool);
bool fifth_pass_enabled();
void reset_fifth_pass_diagnostics();
FifthPassDiagnostics fifth_pass_diagnostics();
bool expose_halation_chain(const double*,const float*,double,int,int,const double*,int,double,const HalationScatterRequest&,float*);
}
