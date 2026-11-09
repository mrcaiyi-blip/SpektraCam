#include "pipeline/dng_stage3_vignette.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace raw_isp::pipeline {
namespace {
// Bernstein convex-hull bounds certify positivity over the entire radius
// interval; subdivide inconclusive intervals instead of sampling/guessing.
bool PositiveGain(const std::array<double, 6> &b, int depth, double margin) {
    const auto bounds = std::minmax_element(b.begin(), b.end());
    if (*bounds.first > margin) return true;
    if (*bounds.second <= margin || depth == 16) return false;
    std::array<double, 6> work = b, left{}, right{};
    left[0] = work[0]; right[5] = work[5];
    for (int level = 1; level <= 5; ++level) {
        for (int i = 0; i <= 5-level; ++i) work[i] = (work[i]+work[i+1])*0.5;
        left[level] = work[0]; right[5-level] = work[5-level];
    }
    return PositiveGain(left, depth+1,margin) && PositiveGain(right, depth+1,margin);
}
double Choose(int n, int k) {
    double result = 1;
    for (int i = 1; i <= k; ++i) result *= double(n-i+1)/i;
    return result;
}
}  // namespace

bool ValidateDngStage3Vignette(const DngStage3Vignette &v, int32_t width,
                             int32_t height, std::string *error) {
    if (error == nullptr) return false;
    error->clear();
    const auto reject = [&](const char *message) { *error=message; return false; };
    if (!v.sourceBeforeStage3) return reject("stage-3 input provenance not established or already corrected");
    if (width <= 0 || height <= 0 || v.imageWidth != width || v.imageHeight != height)
        return reject("stage-3 full-frame geometry mismatch; cropped mapping is unsupported");
    if (!std::isfinite(v.centerX) || !std::isfinite(v.centerY) ||
        v.centerX < 0 || v.centerX > 1 || v.centerY < 0 || v.centerY > 1 ||
        !std::isfinite(v.pixelAspectRatio) || v.pixelAspectRatio <= 0)
        return reject("invalid stage-3 center/pixel aspect");
    for (float c:v.coefficients) if (!std::isfinite(c)) return reject("nonfinite stage-3 coefficient");
    if (std::max(double(width),double(height)/v.pixelAspectRatio) >
        std::sqrt(double(std::numeric_limits<float>::max()))/4)
        return reject("stage-3 geometry outside validated f32 radius range");
    double amplitude=1;
    for (float c:v.coefficients) amplitude+=std::abs(double(c));
    const double numericalMargin=32*std::numeric_limits<float>::epsilon()*amplitude;
    std::array<double, 6> bernstein{};
    for (int k=0;k<=5;++k) {
        bernstein[k]=1;
        for (int j=1;j<=k;++j)
            bernstein[k]+=double(v.coefficients[j-1])*Choose(k,j)/Choose(5,j);
        // Bound intermediate f32 arithmetic, reject unsupported extreme
        // profiles rather than silently clipping a radiometric correction.
        if (!std::isfinite(bernstein[k]) || std::abs(bernstein[k]) > 1.0e6)
            return reject("stage-3 profile outside validated numeric range");
    }
    if (!PositiveGain(bernstein,0,numericalMargin))
        return reject("stage-3 positive f32 gain not certified over full radius");
    return true;
}

std::array<float, 8> DngStage3VignettePacket(const DngStage3Vignette &v) {
    return {v.coefficients[0],v.coefficients[1],v.coefficients[2],v.coefficients[3],
            v.coefficients[4],v.centerX,v.bottomLeftOrigin?1.0f-v.centerY:v.centerY,v.pixelAspectRatio};
}

double DngStage3VignetteGain(const DngStage3Vignette &v, double x, double y) {
    const auto packet=DngStage3VignettePacket(v);
    const double cx=double(packet[5])*v.imageWidth, cy=double(packet[6])*v.imageHeight;
    const double radius=std::hypot(std::max(cx,v.imageWidth-cx),
                                  std::max(cy,v.imageHeight-cy)/packet[7]);
    const double dx=x+0.5-cx, dy=(y+0.5-cy)/packet[7];
    const double r2=(dx*dx+dy*dy)/(radius*radius);
    double sum=0;
    for (int i=4;i>=0;--i) sum=r2*(double(packet[i])+sum);
    return 1+sum;
}
}  // namespace raw_isp::pipeline
