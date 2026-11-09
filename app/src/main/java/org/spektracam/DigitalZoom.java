package org.spektracam;

final class DigitalZoom {
    static float clamp(float ratio) { return Float.isFinite(ratio) ? Math.max(1f, Math.min(64f, ratio)) : 1f; }
    static int[] crop(int width, int height, float ratio) {
        ratio = clamp(ratio);
        int w = Math.max(1, Math.round(width / ratio)), h = Math.max(1, Math.round(height / ratio));
        return new int[]{(width-w)/2, (height-h)/2, w, h};
    }
}
