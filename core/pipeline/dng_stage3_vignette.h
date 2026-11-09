#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace raw_isp::pipeline {

// Explicit metadata from this image's OpcodeList3, not a camera/ISO default.
// Current contract supports one full-frame FixVignetteRadial.
// CFA, stage-2 LSC, DPC and sensor confidence remain in their original domains.
struct DngStage3Vignette {
    std::array<float, 5> coefficients{};
    float centerX = 0.5f;
    float centerY = 0.5f;
    float pixelAspectRatio = 1.0f;
    int32_t imageWidth = 0;
    int32_t imageHeight = 0;
    bool bottomLeftOrigin = false;
    // Caller must establish that the input has not already had stage 3 applied.
    bool sourceBeforeStage3 = false;
};

bool ValidateDngStage3Vignette(const DngStage3Vignette &vignette,
                             int32_t width, int32_t height, std::string *error);
double DngStage3VignetteGain(const DngStage3Vignette &vignette, double x, double y);
// Serialized after the CFA plane as eight float32 words.
// Coefficients[0..4], effective center.x/y, pixel aspect ratio: eight f32 words.
std::array<float, 8> DngStage3VignettePacket(const DngStage3Vignette &vignette);

}  // namespace raw_isp::pipeline
