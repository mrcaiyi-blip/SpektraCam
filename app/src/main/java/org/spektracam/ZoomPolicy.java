package org.spektracam;

/** Total magnification is relative to the main camera; cropping is relative to the chosen lens. */
final class ZoomPolicy {
    final float[] opticalRatios;
    final float minimum, maximum;
    ZoomPolicy(float[] ratios) {
        if (ratios.length == 0) throw new IllegalArgumentException("No lenses");
        opticalRatios = ratios.clone();
        for (float ratio : opticalRatios)
            if (!Float.isFinite(ratio) || ratio <= 0) throw new IllegalArgumentException("Invalid optical ratio");
        for (int i=1;i<opticalRatios.length;i++)
            if (opticalRatios[i] < opticalRatios[i-1]) throw new IllegalArgumentException("Unsorted lenses");
        minimum = opticalRatios[0]; maximum = opticalRatios[opticalRatios.length-1] * 4f;
    }
    float clamp(float zoom) { return Float.isFinite(zoom) ? Math.max(minimum, Math.min(maximum, zoom)) : Math.max(minimum,1f); }
    int lensIndex(float zoom) {
        zoom = clamp(zoom); int index=0;
        for (int i=1;i<opticalRatios.length;i++) if (zoom >= opticalRatios[i]) index=i;
        return index;
    }
    float cropRatio(float zoom) { zoom=clamp(zoom); return Math.max(1f,zoom/opticalRatios[lensIndex(zoom)]); }
}
