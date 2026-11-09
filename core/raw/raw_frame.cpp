#include "raw_frame.h"
#include "kernels/parallel.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace spektracam {
namespace {

float roundingOffset(uint32_t x, uint32_t y, float signal) {
    uint32_t hash = x * 0x9E3779B1u ^ y * 0x85EBCA77u;
    hash ^= hash >> 16;
    hash *= 0x7FEB352Du;
    hash ^= hash >> 15;
    hash *= 0x846CA68Bu;
    hash ^= hash >> 16;
    const float uniform = float(hash & 0x00FFFFFFu) * (1.0f / 16777216.0f);
    const float strength = std::clamp((48.0f - signal) / 32.0f, 0.0f, 1.0f);
    return 0.5f + strength * (uniform - 0.5f);
}

struct GridCoordinate {
    uint32_t low;
    uint32_t high;
    float fraction;
};

GridCoordinate gridCoordinate(uint32_t pixel, uint32_t extent, uint32_t gridExtent) {
    const float coordinate = float(pixel) * float(gridExtent - 1) / float(extent - 1);
    const auto low = std::min(uint32_t(coordinate), gridExtent - 1);
    return {low, std::min(low + 1, gridExtent - 1), coordinate - float(low)};
}

LensShading smoothLensShading(const LensShading& original) {
    LensShading result = original;
    if (original.gains.empty()) return result;
    std::vector<float> horizontal(original.gains.size());
    const size_t row = size_t(original.width) * 4;
    for (int pass = 0; pass < 4; ++pass) {
        for (uint32_t y = 0; y < original.height; ++y) {
            for (uint32_t x = 0; x < original.width; ++x) {
                const size_t base = y * row + size_t(x) * 4;
                const size_t left = x == 0 ? base : base - 4;
                const size_t right = x + 1 == original.width ? base : base + 4;
                for (size_t c = 0; c < 4; ++c) {
                    horizontal[base + c] =
                        (result.gains[left + c] + 2 * result.gains[base + c] + result.gains[right + c]) * 0.25f;
                }
            }
        }
        for (size_t y = 0; y < original.height; ++y) {
            const size_t above = y == 0 ? y : y - 1;
            const size_t below = std::min(y + 1, size_t(original.height - 1));
            for (size_t i = 0; i < row; ++i) {
                result.gains[y * row + i] =
                    (horizontal[above * row + i] + 2 * horizontal[y * row + i] + horizontal[below * row + i]) * 0.25f;
            }
        }
    }
    return result;
}

float lensGain(const LensShading& shading, GridCoordinate x, GridCoordinate y, size_t channel) {
    auto at = [&](uint32_t xx, uint32_t yy) {
        return shading.gains[(size_t(yy) * shading.width + xx) * 4 + channel];
    };
    const float top = at(x.low, y.low) * (1 - x.fraction) + at(x.high, y.low) * x.fraction;
    const float bottom = at(x.low, y.high) * (1 - x.fraction) + at(x.high, y.high) * x.fraction;
    return std::clamp(top * (1 - y.fraction) + bottom * y.fraction, 0.25f, 5.0f);
}

} // namespace

void validateRaw(const RawFrame& frame) {
    constexpr size_t maxPixels = 32 * 1024 * 1024;
    if (!frame.bytes || frame.width < 2 || frame.height < 2 ||
        size_t(frame.width) * frame.height > maxPixels || uint32_t(frame.bayer) > 3 ||
        frame.pixelStride < 2 || frame.size < 2 ||
        frame.pixelStride > (frame.size - 2) / (frame.width - 1)) {
        throw std::invalid_argument("Invalid Bayer RAW geometry");
    }
    const size_t rowBytes = (frame.width - 1) * frame.pixelStride + 2;
    if (frame.rowStride < rowBytes || frame.rowStride > (frame.size - rowBytes) / (frame.height - 1)) {
        throw std::invalid_argument("Truncated Bayer RAW buffer");
    }
    if (!std::isfinite(frame.white) || frame.white <= 0 || frame.white > 65535 ||
        !std::all_of(frame.black.begin(), frame.black.end(), [&](float level) {
            return std::isfinite(level) && level >= 0 && level < frame.white;
        }) ||
        !std::all_of(frame.whiteBalance.begin(), frame.whiteBalance.end(), [](float gain) {
            return std::isfinite(gain) && gain > 0;
        })) {
        throw std::invalid_argument("Invalid RAW calibration");
    }
    if (frame.cameraToLinearSrgb &&
        !std::all_of(frame.cameraToLinearSrgb->begin(), frame.cameraToLinearSrgb->end(),
                     [](float value) { return std::isfinite(value); })) {
        throw std::invalid_argument("Invalid camera color matrix");
    }
    const auto& shading = frame.lensShading;
    if ((shading.width || shading.height || !shading.gains.empty()) &&
        (!shading.width || !shading.height ||
         shading.gains.size() % 4 != 0 ||
         size_t(shading.width) * shading.height != shading.gains.size() / 4 ||
         !std::all_of(shading.gains.begin(), shading.gains.end(),
                      [](float gain) { return std::isfinite(gain) && gain > 0; }))) {
        throw std::invalid_argument("Invalid lens shading map");
    }
    if (frame.vignette) {
        std::string error;
        if (!raw_isp::pipeline::ValidateDngStage3Vignette(*frame.vignette, frame.width, frame.height, &error)) {
            throw std::invalid_argument(error);
        }
    }
}

void expandRaw(const RawFrame& frame, uint32_t* destination) {
    const auto shading = smoothLensShading(frame.lensShading);
    const bool hasShading = !shading.gains.empty();
    constexpr size_t channels[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {1, 3, 0, 2}, {3, 1, 2, 0}};
    const auto& parityChannels = channels[uint32_t(frame.bayer)];
    std::vector<GridCoordinate> columns;
    if (hasShading) {
        columns.reserve(frame.width);
        for (uint32_t x = 0; x < frame.width; ++x) {
            columns.push_back(gridCoordinate(x, frame.width, shading.width));
        }
    }
    spk::parallel_for(0, int(frame.height), [&](int begin, int end) {
        for (int y = begin; y < end; ++y) {
            const auto* row = frame.bytes + size_t(y) * frame.rowStride;
            auto* output = destination + size_t(y) * frame.width;
            GridCoordinate gridY{};
            if (hasShading) gridY = gridCoordinate(y, frame.height, shading.height);
            for (uint32_t x = 0; x < frame.width; ++x) {
                const auto* sample = row + x * frame.pixelStride;
                const size_t parity = (y & 1) * 2 + (x & 1);
                const float black = frame.black[parity];
                const float signal = std::max(0.0f, float(sample[0] | (uint16_t(sample[1]) << 8)) - black);
                const float gain = hasShading ? lensGain(shading, columns[x], gridY, parityChannels[parity]) : 1;
                const float corrected = black + signal * gain;
                const float clamped = std::clamp(corrected, black, black + 8 * (frame.white - black));
                const float offset = hasShading ? roundingOffset(x, y, signal) : 0.5f;
                output[x] = uint32_t(std::floor(clamped + offset));
            }
        }
    });
    if (frame.vignette) {
        const auto packet = raw_isp::pipeline::DngStage3VignettePacket(*frame.vignette);
        std::memcpy(destination + size_t(frame.width) * frame.height, packet.data(), sizeof(packet));
    }
}

} // namespace spektracam
