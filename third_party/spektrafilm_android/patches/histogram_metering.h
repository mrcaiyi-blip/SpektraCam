// LutCam addition to the pinned GPLv3 Spektrafilm engine.
// Matches the Python Workbench histogram_middle_gray strategy. This is a
// linear-input meter, without the classic ISP's ACR3/night/display constraints.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

namespace spk {
inline double histogram_middle_gray_ev(std::vector<double> samples) {
    if (samples.empty()) return 0.0;
    std::array<double, 128> histogram{};
    for (double& y : samples) {
        if (!std::isfinite(y)) return std::numeric_limits<double>::quiet_NaN();
        y = std::clamp(y, 0.0, 1.0);
        ++histogram[std::min(static_cast<int>(y * 128), 127)];
    }
    std::sort(samples.begin(), samples.end());
    const size_t n = samples.size();
    if (samples.back() <= 0.0) return 0.0;
    double logMean = 0.0;
    for (size_t i = 0; i < histogram.size(); ++i)
        logMean += histogram[i] / n * std::log((i + 0.5) / 128.0);
    const double geometric = std::exp(logMean);
    const size_t begin = std::min(size_t(std::floor(0.05 * n)), n - 1);
    const size_t end = std::min(std::max(begin + 1, size_t(std::ceil(0.95 * n))), n);
    double trimmed = 0.0;
    for (size_t i = begin; i < end; ++i) trimmed += samples[i];
    trimmed /= end - begin;
    double mean = 0.0;
    if (trimmed <= 0.001) {
        for (double y : samples) mean += y;
        mean /= n;
    }
    const double robust = trimmed > 0.001 ? trimmed : mean;
    const double median = std::max(samples[size_t(std::floor(0.5 * (n - 1) + 0.5))], 0.001);
    const double anchor = std::clamp(
            0.55 * std::max(geometric > 0.001 ? geometric : robust, 0.001) +
            0.30 * std::max(median > 0.001 ? median : robust, 0.001) +
            0.15 * std::max(robust, 0.001), 0.006, 0.82);
    return std::clamp(std::log2(0.184 / anchor), -4.0, 4.0);
}
} // namespace spk
