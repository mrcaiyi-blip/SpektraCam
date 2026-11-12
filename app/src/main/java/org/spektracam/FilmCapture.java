package org.spektracam;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class FilmCapture {
    private static final String RESOURCE_VERSION = "15fb1209";
    private static boolean warmed;

    static synchronized void warmUp(Context context, FilmLook look, String meter) throws IOException {
        if (warmed) return;
        File assets = installResources(context);
        // A small, synthetic Bayer frame primes the same film/GPU path as the first shot.
        int side = 256;
        ByteBuffer raw = ByteBuffer.allocateDirect(side * side * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < raw.capacity(); offset += 2) raw.putShort(offset, (short) 512);
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        try {
            String error = NativeFilm.render(raw, side, side, side * 2, 2, 0,
                    new float[4], 4095, new float[]{1, 1, 1, 1},
                    null, null, 0, 0, true, assets.getPath(), look.id, look.paper, meter, bitmap);
            if (error != null) throw new IOException(error);
            warmed = true;
        } finally {
            bitmap.recycle();
        }
    }

    static Bitmap render(Context context, RawCamera.Lens lens, RawCamera.Frame raw,
            CaptureResult result, FilmLook look, String meter,
            float zoom, int rotation, FilmBorder border, FilmBorder.Caption caption) throws IOException {
        CameraCharacteristics info = lens.info;
        Integer cfa = info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        float[] black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (black == null || black.length != 4) {
            BlackLevelPattern pattern = info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            if (pattern == null) throw new IOException(context.getString(R.string.raw_black_level));
            int[] levels = new int[4];
            pattern.copyTo(levels, 0);
            black = new float[] {levels[0], levels[1], levels[2], levels[3]};
        } else {
            // Camera2 reports dynamic black in the sensor CFA site order.
            black = black.clone();
        }
        for (int i = 0; i < black.length; ++i) black[i] = Math.round(black[i]);
        Integer white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (white == null) white = info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (white == null || cfa == null) throw new IOException(context.getString(R.string.raw_white_level_cfa));

        RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        float[] whiteBalance = {1, 1, 1, 1};
        if (gains != null) gains.copyTo(whiteBalance, 0);
        float[] matrix = cameraMatrix(info, result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM));

        LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        float[] shading = null;
        int shadingWidth = 0, shadingHeight = 0;
        if (map != null) {
            shading = new float[map.getGainFactorCount()];
            map.copyGainFactors(shading, 0);
            shadingWidth = map.getColumnCount();
            shadingHeight = map.getRowCount();
        }

        File assets = installResources(context);
        Bitmap bitmap = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888);
        try {
            String error = NativeFilm.render(raw.data, raw.width, raw.height, raw.rowStride, raw.pixelStride,
                    cfa, black, white, whiteBalance, matrix, shading, shadingWidth, shadingHeight,
                    true, assets.getPath(), look.id, look.paper, meter, bitmap);
            if (error != null) throw new IOException(error);
            int[] crop = DigitalZoom.crop(raw.width, raw.height, zoom);
            if (border != FilmBorder.NONE) {
                Bitmap framed = border.render(bitmap,
                        new Rect(crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3]), rotation, caption);
                bitmap.recycle();
                return framed;
            }
            Matrix rotate = new Matrix();
            rotate.setRotate(rotation);
            Bitmap finalImage = Bitmap.createBitmap(bitmap, crop[0], crop[1], crop[2], crop[3], rotate, false);
            if (finalImage != bitmap) bitmap.recycle();
            return finalImage;
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            bitmap.recycle();
            throw error;
        }
    }

    private static float[] cameraMatrix(CameraCharacteristics info, ColorSpaceTransform color) {
        if (color != null) {
            float[] matrix = matrixValues(color);
            if (!isIdentity(matrix)) return matrix;
        }
        ColorSpaceTransform forward = info.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2);
        if (forward == null) forward = info.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1);
        if (forward != null) {
            float[] xyz = matrixValues(forward);
            float[] srgbFromXyzD50 = {3.1338561f, -1.6168667f, -0.4906146f,
                    -0.9787684f, 1.9161415f, 0.0334540f, 0.0719453f, -0.2289914f, 1.4052427f};
            float[] matrix = new float[9];
            for (int row = 0; row < 3; ++row) {
                for (int column = 0; column < 3; ++column) {
                    for (int k = 0; k < 3; ++k) matrix[row * 3 + column] += srgbFromXyzD50[row * 3 + k] * xyz[k * 3 + column];
                }
            }
            if (!isIdentity(matrix)) return matrix;
        }
        return new float[]{1.55f, -.32f, -.23f, -.18f, 1.35f, -.17f, -.04f, -.38f, 1.42f};
    }

    private static float[] matrixValues(ColorSpaceTransform color) {
        float[] matrix = new float[9];
        for (int y = 0; y < 3; ++y) {
            for (int x = 0; x < 3; ++x) matrix[y * 3 + x] = color.getElement(x, y).floatValue();
        }
        return matrix;
    }

    private static boolean isIdentity(float[] matrix) {
        for (int i = 0; i < 9; ++i) {
            if (Math.abs(matrix[i] - (i % 4 == 0 ? 1 : 0)) > 0.0001f) return false;
        }
        return true;
    }

    static synchronized File installResources(Context context) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), "spektra-" + RESOURCE_VERSION);
        File complete = new File(root, ".complete");
        if (!complete.isFile()) {
            AssetManager assets = context.getAssets();
            copy(context, assets, "spektra/catalog.json", new File(root, "catalog.json"));
            for (String directory : new String[] {"profiles", "filters", "luts"}) {
                copy(context, assets, "spektra/" + directory, new File(root, directory));
            }
            if (!complete.createNewFile() && !complete.isFile()) throw new IOException(context.getString(R.string.install_film_resources));
        }
        return root;
    }

    private static void copy(Context context, AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException(context.getString(R.string.create_resource_directory));
            for (String child : children) copy(context, assets, path + "/" + child, new File(target, child));
        } else {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(context.getString(R.string.create_resource_directory));
            try (InputStream input = assets.open(path); java.io.OutputStream output = new java.io.FileOutputStream(target)) {
                byte[] bytes = new byte[32 * 1024];
                for (int count; (count = input.read(bytes)) >= 0; ) output.write(bytes, 0, count);
            }
        }
    }
}
