// LutCam local scan-tail optimization, GPL-3.0-only. Upstream engine stays intact.
#pragma once
#include <cstdint>
namespace spk::gpu {
struct ScanTailDiagnostics {
    bool attempted=false,engaged=false,shared_workspace=false;
    int self_check=0;
    double allocation_ms=0,self_check_max_error=0,upload_ms=0,compute_wait_ms=0,readback_ms=0;
    uint32_t dispatches=0,submissions=0;
    uint64_t bytes_up=0,bytes_down=0,avoided_workspace_bytes=0;
    const char* reason="not_requested";
};
// Serialized engine worker owns this per-thread A/B policy and render counters.
void set_scan_tail_enabled(bool enabled);
bool scan_tail_enabled();
void set_shared_workspace_enabled(bool enabled);
bool shared_workspace_enabled();
// Fourth-pass bundle also controls bit-identical metering sample optimization.
void set_fourth_pass_enabled(bool enabled);
bool fourth_pass_enabled();
void set_scan_parallel_transfer_enabled(bool enabled);
bool scan_parallel_transfer_enabled();
void reset_scan_tail_diagnostics();
ScanTailDiagnostics scan_tail_diagnostics();
// Complete common SDR scan tail: optional additive viewing glare, reflected FIR
// unsharp, CAT02 round-trip matrix, sRGB transfer and clip. No model parameter changes.
// Returns false WITHOUT modifying output on unsupported parameters, self-check
// failure, allocation/dispatch failure, or nonfinite result.
bool scan_tail(const float* linear,const float* glare,int width,int height,
               const double glare_color[3],const double matrix[9],double sigma,
               double amount,float* encoded);
}
