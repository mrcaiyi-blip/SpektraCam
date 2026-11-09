#pragma once

#include "spektra.h"
#include <cstddef>
#include <cstdint>
#include <cmath>

namespace lutcam::film {

struct Look {
    const char* film;
    const char* paper; // Empty for directly scanned reversal film.
    bool gpu;
    int encoding = 0; // transfer (low 4 bits) | primaries (high bits); 0 = encoded sRGB.
    const char* exposureMethod = "center_weighted";
};

bool valid_exposure_method(const char* method);

// A frozen AE value scales scene-linear input. It is NOT manual film exposure
// compensation, which also changes automatic print exposure on negative stocks.
void apply_frozen_auto_exposure(float* rgb, size_t components, float exposureEv);

// Input and output are opaque, encoded sRGB RGBA8 in the same row order.
// Camera ISP tone mapping remains baked into this display-referred input.
void display_to_prophoto(const uint8_t* rgba, size_t pixels, float* rgb, int encoding = 0);
spk_status render_linear(spk_engine* engine, float* prophoto, int width, int height,
                         const Look& look, bool autoExposure, spk_image* output,
                         spk_cancel_check cancelled = nullptr, void* user = nullptr, float fixedExposureEv = NAN);
spk_status render_rgba(spk_engine* engine, const uint8_t* input, uint8_t* output,
                       int width, int height, const Look& look,
                       spk_cancel_check cancelled = nullptr, void* user = nullptr);

} // namespace lutcam::film
