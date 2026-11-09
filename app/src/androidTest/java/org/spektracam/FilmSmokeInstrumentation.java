package org.spektracam;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** Exercises the installed app's assets, native library and Android Bitmap output. */
public final class FilmSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runFilmSmoke();
            result.putString("stream", "\nPASS: Android RAW to two Portra 400 papers and Provia 100F\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | LinkageError | AssertionError failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runFilmSmoke() throws Exception {
        int width = 139, height = 271, rowStride = width * 2 + 6;
        ByteBuffer raw = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                raw.putShort(y * rowStride + x * 2, (short) (160 + y + x));
            }
        }

        Context target = getTargetContext();
        List<FilmLook> looks = FilmLook.load(target.getAssets());
        String root = FilmCapture.installResources(target).getPath();
        float[] black = {100, 100, 100, 100};
        float[] whiteBalance = {1, 1, 1, 1};
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            FilmLook portra = looks.stream().filter(entry -> "kodak_portra_400".equals(entry.id))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing Portra 400"));
            FilmLook provia = looks.stream().filter(entry -> "fujifilm_provia_100f".equals(entry.id))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing Provia 100F"));
            FilmLook alternate = portra.selectingPaper("kodak_supra_endura");
            int[] defaultPixels = null;
            for (FilmLook look : new FilmLook[]{portra, alternate, provia}) {
                // Reset the bitmap so a render that skips rows cannot pass on stale pixels.
                output.eraseColor(0);
                String error = NativeFilm.render(raw, width, height, rowStride, 2, 0, black, 1000,
                        whiteBalance, null, null, 0, 0, false, root, look.id, look.paper, null, output);
                if (error != null) throw new AssertionError(look.id + ": " + error);
                int[] pixels = new int[width * height];
                output.getPixels(pixels, 0, width, 0, 0, width, height);
                boolean hasTone = false;
                for (int pixel : pixels) {
                    if ((pixel >>> 24) != 255) throw new AssertionError(look.id + ": incomplete bitmap");
                    int rgb = pixel & 0x00ffffff;
                    hasTone |= rgb != 0 && rgb != 0x00ffffff;
                }
                if (!hasTone) throw new AssertionError(look.id + ": no rendered color");
                if (look == portra) defaultPixels = pixels;
                if (look == alternate && Arrays.equals(defaultPixels, pixels)) {
                    throw new AssertionError("Selecting a different paper did not change Portra 400 output");
                }
            }
        } finally {
            output.recycle();
        }
    }
}
