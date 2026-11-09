#include "spektracam.h"
#include "raw_frame.h"
#include "film/film_output.h"
#include "film/spektrafilm_adapter.h"

#include <algorithm>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

class FilmEngine {
public:
    void render(const char* root, const char* film, const char* paper, const char* meter,
                const spektracam::RawFrame& frame, std::vector<float>& linear, uint8_t* rgba) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!engine_ || root_ != root) {
            spk_engine* value = nullptr;
            const auto status = spk_engine_create(root, &value);
            Engine candidate(value, spk_engine_destroy);
            if (status != SPK_OK) throw std::runtime_error(spk_last_error_message());
            root_ = root;
            engine_ = std::move(candidate);
        }
        const lutcam::film::Look look{film, paper, true, 0, meter};
        spk_image result{};
        struct Output {
            spk_image& image;
            ~Output() { spk_image_free(&image); }
        } output{result};
        const auto status = lutcam::film::render_linear(engine_.get(), linear.data(), int(frame.width),
                                                         int(frame.height), look, true, &result);
        if (status != SPK_OK) throw std::runtime_error(spk_last_error_message());
        if (!result.data || result.width != int(frame.width) || result.height != int(frame.height)) {
            throw std::runtime_error("Unexpected film output dimensions");
        }
        lutcam::film::to_rgba(result.data, result.width, result.height, rgba, size_t(frame.width) * 4);
    }

private:
    using Engine = std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)>;
    Engine engine_{nullptr, spk_engine_destroy};
    std::string root_;
    std::mutex mutex_;
};

spektracam::RawFrame borrowFrame(const sc_raw_frame& input) {
    spektracam::RawFrame frame;
    frame.bytes = input.data;
    frame.size = input.size;
    frame.rowStride = input.row_stride;
    frame.pixelStride = input.pixel_stride;
    frame.width = input.width;
    frame.height = input.height;
    frame.bayer = spektracam::Bayer(input.bayer);
    std::copy_n(input.black, 4, frame.black.begin());
    frame.white = input.white;
    std::copy_n(input.white_balance, 4, frame.whiteBalance.begin());
    if (input.has_matrix) {
        auto& matrix = frame.cameraToLinearSrgb.emplace();
        std::copy_n(input.matrix, 9, matrix.begin());
    }
    if (input.lens_shading || input.shading_width || input.shading_height) {
        if (!input.lens_shading || !input.shading_width || !input.shading_height ||
            input.shading_width > 1024 || input.shading_height > 1024) {
            throw std::invalid_argument("Invalid RAW lens shading");
        }
        frame.lensShading.width = input.shading_width;
        frame.lensShading.height = input.shading_height;
        const size_t count = size_t(input.shading_width) * input.shading_height * 4;
        frame.lensShading.gains.assign(input.lens_shading, input.lens_shading + count);
    }
    if (input.has_vignette) {
        auto& vignette = frame.vignette.emplace();
        std::copy_n(input.vignette, 5, vignette.coefficients.begin());
        vignette.centerX = input.vignette_center_x;
        vignette.centerY = input.vignette_center_y;
        vignette.imageWidth = int32_t(input.width);
        vignette.imageHeight = int32_t(input.height);
        vignette.sourceBeforeStage3 = true;
    }
    frame.highlightChromaBlend = input.highlight_chroma_blend != 0;
    return frame;
}

} // namespace

int sc_render_raw(const sc_raw_frame* input, const char* root, const char* film,
                  const char* paper, const char* meter, uint8_t* rgba, size_t rgbaSize,
                  char* error, size_t errorSize) {
    if (error && errorSize) error[0] = '\0';
    try {
        if (!input || !root || !*root || !film || !*film || !rgba ||
            size_t(input->width) * input->height > rgbaSize / 4) {
            throw std::invalid_argument("Missing RAW render inputs or output storage");
        }
        const char* method = meter && *meter ? meter : "histogram_middle_gray";
        if (!lutcam::film::valid_exposure_method(method)) {
            throw std::invalid_argument("Unknown film exposure method");
        }
        const auto frame = borrowFrame(*input);
        auto linear = spektracam::developRaw(frame);
        static FilmEngine engine;
        engine.render(root, film, paper ? paper : "", method, frame, linear, rgba);
        return 1;
    } catch (const std::exception& failure) {
        if (error && errorSize) std::snprintf(error, errorSize, "%s", failure.what());
        return 0;
    }
}
