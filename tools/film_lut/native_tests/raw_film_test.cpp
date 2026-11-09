#include "raw/raw_frame.h"
#include "spektracam.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>

namespace {
void require(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}
}

int main(int argc, char** argv) try {
    require(argc == 2, "Supply film resource directory");
    constexpr uint32_t width = 139, height = 271, rowStride = width * 2 + 6;
    std::vector<uint8_t> input(size_t(rowStride) * height);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            // A flat neutral above sensor white tests both headroom and stitching
            // across the two 128-row shader strip boundaries.
            const uint16_t value = 1450;
            const size_t offset = size_t(y) * rowStride + x * 2;
            input[offset] = uint8_t(value & 255);
            input[offset + 1] = uint8_t(value >> 8);
        }
    }
    spektracam::RawFrame raw;
    raw.bytes = input.data();
    raw.size = input.size();
    raw.width = width;
    raw.height = height;
    raw.rowStride = rowStride;
    raw.white = 1000;
    raw.black.fill(100);
    raw.highlightChromaBlend = false;
    const auto linear = spektracam::developRaw(raw);
    require(linear.size() == size_t(width) * height * 3, "Unexpected linear output dimensions");
    require(std::all_of(linear.begin(), linear.end(), [](float value) {
        return std::isfinite(value) && std::abs(value - 1.5f) < 0.002f;
    }), "Neutral reconstruction lost headroom or introduced a strip seam");

    // Repeat on the reused Vulkan session with nonuniform rows.
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            const uint16_t value = uint16_t(160 + y + x);
            const size_t offset = size_t(y) * rowStride + x * 2;
            input[offset] = uint8_t(value & 255);
            input[offset + 1] = uint8_t(value >> 8);
        }
    }
    const auto gradient = spektracam::developRaw(raw);
    for (uint32_t y : {1u, 127u, 128u, 255u, 256u, 269u}) {
        for (uint32_t x : {1u, 30u, 131u}) {
            const float expected = float(60 + x + y) / 900;
            for (size_t c = 0; c < 3; ++c) {
                require(std::abs(gradient[(size_t(y) * width + x) * 3 + c] - expected) < 0.005f,
                        "Gradient reconstruction misordered rows or channels");
            }
        }
    }

    sc_raw_frame frame{};
    frame.data = input.data();
    frame.size = input.size();
    frame.width = width;
    frame.height = height;
    frame.row_stride = rowStride;
    frame.pixel_stride = 2;
    frame.white = 1000;
    std::fill_n(frame.black, 4, 100);
    std::fill_n(frame.white_balance, 4, 1);
    std::vector<uint8_t> rgba(size_t(width) * height * 4);
    char error[512]{};
    for (const auto& look : {std::pair{"kodak_portra_400", "kodak_portra_endura"},
                             std::pair{"kodak_portra_400", "fujifilm_crystal_archive_typeii"},
                             std::pair{"kodak_vision3_500t", "kodak_2393"},
                             std::pair{"fujifilm_provia_100f", ""}}) {
        require(sc_render_raw(&frame, argv[1], look.first, look.second, nullptr,
                              rgba.data(), rgba.size(), error, sizeof(error)) == 1, error);
        for (size_t i = 3; i < rgba.size(); i += 4) require(rgba[i] == 255, "Output alpha is not opaque");
        require(std::any_of(rgba.begin(), rgba.end(), [](uint8_t value) { return value > 0 && value < 255; }),
                "Film output has no rendered color");
    }
    frame.size = 1;
    require(sc_render_raw(&frame, argv[1], "kodak_portra_400", "kodak_portra_endura", nullptr,
                          rgba.data(), rgba.size(), error, sizeof(error)) == 0 && *error,
            "Truncated RAW was accepted");
    std::cout << "PASS: Vulkan single-frame RAW to film, negative and slide, across strip boundaries\n";
    return 0;
} catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
}
