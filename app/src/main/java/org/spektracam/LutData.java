package org.spektracam;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** RGB order is identical to the native LUT pass: red varies fastest. */
final class LutData {
    final int size;
    final FloatBuffer rgb;
    private LutData(int size, float[] values) {
        this.size = size;
        rgb = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(values);
        rgb.flip();
    }

    static LutData load(AssetManager assets, String path) throws Exception {
        byte[] bytes;
        try (InputStream stream = assets.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 16 || input.getInt() != 0x54554c50 || input.getInt() != 1)
            throw new IllegalArgumentException("Invalid PLUT header");
        int size = input.getInt(), format = input.getInt();
        if (size < 2 || size > 128 || format != 1 || bytes.length != 16 + size*size*size*6)
            throw new IllegalArgumentException("Invalid PLUT data");
        float[] values = new float[size*size*size*3];
        for (int i = 0; i < values.length; i++)
            values[i] = (input.getShort() & 0xffff) / 65535f;
        return new LutData(size, values);
    }
}
