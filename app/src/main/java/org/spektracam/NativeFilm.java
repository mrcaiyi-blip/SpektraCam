package org.spektracam;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

final class NativeFilm {
    static { System.loadLibrary("spektracam_jni"); }

    static native String render(ByteBuffer raw, int width, int height, int rowStride,
            int pixelStride, int bayer, float[] black, float white, float[] whiteBalance,
            float[] matrix, float[] shading, int shadingWidth, int shadingHeight,
            boolean highlights, String root, String film, String paper, String meter, Bitmap output);
}
