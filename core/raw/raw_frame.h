#pragma once

#include "pipeline/dng_stage3_vignette.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace spektracam {

enum class Bayer : uint32_t { rggb, grbg, gbrg, bggr };

struct LensShading {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<float> gains; // R, green-even, green-odd, B per grid point.
};

struct RawFrame {
    const uint8_t* bytes = nullptr; // Borrowed little-endian RAW16.
    size_t size = 0;
    size_t rowStride = 0;
    size_t pixelStride = 2;
    uint32_t width = 0;
    uint32_t height = 0;
    Bayer bayer = Bayer::rggb;
    std::array<float, 4> black{};
    float white = 0;
    std::array<float, 4> whiteBalance{1, 1, 1, 1};
    std::optional<std::array<float, 9>> cameraToLinearSrgb;
    std::optional<raw_isp::pipeline::DngStage3Vignette> vignette;
    LensShading lensShading;
    bool highlightChromaBlend = true;
};

void validateRaw(const RawFrame& frame);
void expandRaw(const RawFrame& frame, uint32_t* destination);
void prepareRaw();

// Contains interleaved, scene-linear ProPhoto RGB with highlight headroom.
std::vector<float> developRaw(const RawFrame& frame);

} // namespace spektracam
