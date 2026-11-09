#ifndef SPEKTRACAM_H
#define SPEKTRACAM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const uint8_t* data;
    size_t size;
    size_t row_stride;
    size_t pixel_stride;
    uint32_t width, height;
    uint32_t bayer; // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float black[4];
    float white;
    float white_balance[4]; // R, green-even, green-odd, B
    float matrix[9]; // White-balanced camera RGB to linear sRGB.
    uint8_t has_matrix;
    const float* lens_shading;
    uint32_t shading_width, shading_height;
    float vignette[5];
    float vignette_center_x, vignette_center_y;
    uint8_t has_vignette;
    uint8_t highlight_chroma_blend;
} sc_raw_frame;

// Writes tightly packed opaque sRGB RGBA8. Pointers are borrowed for the call.
// A null or empty metering method selects histogram_middle_gray.
int sc_render_raw(const sc_raw_frame* frame, const char* resource_root,
                  const char* film, const char* paper, const char* meter,
                  uint8_t* rgba, size_t rgba_size, char* error, size_t error_size);

#ifdef __cplusplus
}
#endif

#endif // SPEKTRACAM_H
