#include "runtime/stages/autoexposure.h"
#include "spektra.h"
#include "metering_goldens.h"
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <vector>

int main(int argc, char** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Expected assets");
        spk_engine* engine = nullptr;
        if (spk_engine_create(argv[1], &engine) != SPK_OK) throw std::runtime_error("Engine failed");
        for (const auto& g : goldens) {
            std::vector<float> image(size_t(g.w) * g.h * 3);
            for (size_t i = 0; i < image.size(); ++i) {
                double y = 0;
                if (g.pattern == 1) y = .002 + (i % 11) * .0001;
                if (g.pattern == 2) y = (i / 3) % 101 == 0 ? 3. :
                        (i / 3) % 13 == 0 ? .3 + (i % 7) * .07 : .004 + (i % 17) * .0003;
                if (g.pattern == 3) y = .001 + (i * 7919 % 4093) / 1701.;
                image[i] = float(y);
            }
            const auto original = image;
            for (int m = 0; m < 8; ++m) {
                spk::AeMethod method{};
                if (!spk::ae_method_from_string(methods[m], &method)) throw std::runtime_error("Missing method");
                spk_params p{}; spk_default_params(&p);
                p.auto_exposure = 1; p.auto_exposure_method = methods[m]; p.input_cctf_decoding = 0;
                spk_image input{image.data(), g.w, g.h, SPK_CS_PROPHOTO};
                double actual = 0;
                if (spk_meter_exposure_ev(engine, &input, &p, &actual) != SPK_OK ||
                        !std::isfinite(actual) || std::abs(actual - g.ev[m]) > 1e-9) {
                    std::fprintf(stderr, "%s %dx%d: %.17g != %.17g\n", methods[m],g.w,g.h,actual,g.ev[m]);
                    throw std::runtime_error("Python oracle mismatch");
                }
                std::vector<double> wide(image.begin(), image.end());
                double applied = spk::apply_auto_exposure(wide.data(),g.w,g.h,
                        spk::AeColorSpace::kProPhotoRGB,false,method,true);
                if (actual != applied || image != original) throw std::runtime_error("Meter/render drift or mutated input");
                for (size_t i = 0; i < wide.size(); ++i)
                    if (wide[i] != double(image[i]) * std::pow(2.,actual)) throw std::runtime_error("Gain not applied exactly once");
            }
        }
        spk_engine_destroy(engine);
        std::puts("PASS: 8 methods x 5 Python fixtures; black/night/HDR/antialias; f32/f64 exact gain parity");
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr,"FAIL %s\n",e.what());return 1; }
}
