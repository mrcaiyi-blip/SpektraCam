// LutCam third performance pass. GPL-3.0-only.
#pragma once
#include <cstdint>
#include "gpu/vulkan_compute.h"
namespace spk::gpu {
struct ThirdPassDiagnostics {
    bool print_tail=false,dir_f32=false,spatial_parallel=false;
    int spatial_check=0;
    double spatial_max_error=0;
};
void set_third_pass_enabled(bool enabled);
bool third_pass_enabled();
bool prefer_spatial_gpu();
void reset_third_pass_diagnostics();
ThirdPassDiagnostics third_pass_diagnostics();
void note_print_tail();
void note_dir_f32();
bool halation_scatter_f32(const HalationScatterRequest&,float*,HalationScatterDiagnostics*);
}
