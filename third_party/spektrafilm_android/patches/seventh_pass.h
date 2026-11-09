// LutCam padded transpose, GPL-3.0-only.
#pragma once
namespace spk::gpu {
struct SeventhPassDiagnostics {bool attempted=false,engaged=false;int self_check=0;double self_error=0;const char* reason="not_requested";};
void set_seventh_pass_enabled(bool);
bool seventh_pass_enabled();
void reset_seventh_pass_diagnostics();
SeventhPassDiagnostics seventh_pass_diagnostics();
}
