package org.spektracam;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
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
            runFilmBorderSmoke();
            result.putString("stream", "\nPASS: Android RAW, film papers, and upright film borders\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | LinkageError | AssertionError failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runFilmBorderSmoke() {
        int width = 268, height = 336;
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] colors = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                colors[y * width + x] = 0xff000000 | (x & 255) << 16 | (y & 255) << 8 | (x + y) & 255;
            }
        }
        source.setPixels(colors, 0, width, 0, 0, width, height);
        try {
            if (FilmBorder.NONE.preview(source, FilmBorder.Caption.EMPTY) != source) {
                throw new AssertionError("No border copied the sample");
            }
            Rect crop = new Rect(12, 7, 252, 327);
            for (int degrees : new int[]{0, 90, 180, 270}) {
                Matrix rotation = new Matrix();
                rotation.setRotate(degrees);
                Bitmap upright = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height(), rotation, false);
                try {
                    assertFramedCrop(source, crop, degrees, upright, FilmBorder.PRINT, 12, 12, 12);
                    assertFramedCrop(source, crop, degrees, upright, FilmBorder.INSTANT, 12, 12, 34);
                    boolean portrait = upright.getWidth() < upright.getHeight();
                    int side = portrait ? 55 : 20, top = portrait ? 20 : 55;
                    assertFramedCrop(source, crop, degrees, upright, FilmBorder.STRIP, side, top, top);
                } finally {
                    upright.recycle();
                }
            }
            assertBorderText(source, FilmBorder.STRIP);
            assertBorderText(source, FilmBorder.PRINT);
            assertBorderText(source, FilmBorder.INSTANT);
        } finally {
            source.recycle();
        }
    }

    private void assertFramedCrop(Bitmap source, Rect crop, int degrees, Bitmap upright,
                                  FilmBorder border, int side, int top, int bottom) {
        FilmBorder.Caption caption = new FilmBorder.TextSettings(FilmBorder.TextMode.DEFAULT, "").caption(border, "");
        Bitmap framed = border.render(source, crop, degrees, caption);
        try {
            int width = upright.getWidth(), height = upright.getHeight();
            if (framed.getWidth() != width + 2 * side || framed.getHeight() != height + top + bottom) {
                throw new AssertionError(border + " dimensions at " + degrees + " degrees");
            }
            int[] before = new int[width * height], after = new int[before.length];
            upright.getPixels(before, 0, width, 0, 0, width, height);
            framed.getPixels(after, 0, width, side, top, width, height);
            if (!Arrays.equals(before, after)) {
                throw new AssertionError(border + " changed or clipped the photo at " + degrees + " degrees");
            }
            if (border == FilmBorder.STRIP) {
                boolean portrait = width < height;
                int along = portrait ? framed.getHeight() : framed.getWidth();
                int across = portrait ? framed.getWidth() : framed.getHeight();
                int first = 0, last = 0;
                for (int pixel = 0; pixel < along; pixel++) {
                    if ((portrait ? framed.getPixel(30, pixel) : framed.getPixel(pixel, 30)) == 0xffeeebe3) first++;
                    if ((portrait ? framed.getPixel(across - 31, pixel) : framed.getPixel(pixel, across - 31))
                            == 0xffeeebe3) last++;
                }
                if (first == 0 || last == 0) throw new AssertionError("Missing perforations along the long edges");
            }
        } finally {
            framed.recycle();
        }
    }

    private void assertBorderText(Bitmap source, FilmBorder border) {
        FilmBorder.Caption custom = new FilmBorder.TextSettings(FilmBorder.TextMode.CUSTOM, "片场 2026")
                .caption(border, "Portra 400");
        FilmBorder.Caption hidden = new FilmBorder.TextSettings(FilmBorder.TextMode.HIDDEN, "片场 2026")
                .caption(border, "Portra 400");
        Bitmap withText = border.preview(source, custom);
        Bitmap withoutText = border.preview(source, hidden);
        try {
            if (withText.getWidth() != withoutText.getWidth() || withText.getHeight() != withoutText.getHeight()
                    || withText.sameAs(withoutText)) {
                throw new AssertionError("Border text should change only the drawing for " + border);
            }
        } finally {
            withText.recycle();
            withoutText.recycle();
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
