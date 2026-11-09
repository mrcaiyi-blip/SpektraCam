package org.spektracam;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CaptureGalleryStore {
    private static final String MEDIA_FOLDER = "DCIM/SpektraCam/";
    private static final String FILE_PREFIX = "SpektraCam_";

    static final class Photo {
        final Uri uri;
        final String primary;
        final String secondary;
        final long date;
        final File localCopy;

        Photo(Uri uri, String primary, String secondary, long date, File localCopy) {
            this.uri = uri;
            this.primary = primary;
            this.secondary = secondary;
            this.date = date;
            this.localCopy = localCopy;
        }
    }

    private static SharedPreferences index(Context context) {
        return context.getSharedPreferences("capture_gallery", Context.MODE_PRIVATE);
    }

    static void remember(Context context, Uri uri, File localCopy, String primary, String secondary) {
        if (uri == null) return;
        try {
            JSONObject record = new JSONObject();
            record.put("primary", primary);
            record.put("secondary", secondary);
            record.put("date", System.currentTimeMillis());
            if (localCopy != null) record.put("local", localCopy.getAbsolutePath());
            index(context).edit().putString(uri.toString(), record.toString()).apply();
        } catch (JSONException error) {
            Log.w("CaptureGallery", "Capture metadata could not be saved", error);
        }
    }

    static List<Photo> photos(Context context) {
        Map<Uri, Photo> found = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : index(context).getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            try {
                JSONObject record = new JSONObject((String) entry.getValue());
                Uri uri = Uri.parse(entry.getKey());
                if (!"content".equals(uri.getScheme())) continue;
                String local = record.optString("local");
                found.put(uri, new Photo(uri, record.optString("primary"), record.optString("secondary"),
                        record.optLong("date"), local.isEmpty() ? null : new File(local)));
            } catch (JSONException ignored) {
                // An unreadable record does not prevent other captures from loading.
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] columns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED};
            String where = MediaStore.Images.Media.RELATIVE_PATH + "=? AND "
                    + MediaStore.Images.Media.OWNER_PACKAGE_NAME + "=? AND "
                    + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? AND "
                    + MediaStore.Images.Media.IS_PENDING + "=0";
            try (Cursor cursor = context.getContentResolver().query(collection, columns, where,
                    new String[]{MEDIA_FOLDER, context.getPackageName(), FILE_PREFIX + "%.jpg"}, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                        found.putIfAbsent(uri, new Photo(uri, "", "", cursor.getLong(1) * 1000, null));
                    }
                }
            } catch (RuntimeException error) {
                Log.w("CaptureGallery", "Could not read earlier captures from MediaStore", error);
            }
        }
        List<Photo> photos = new ArrayList<>(found.values());
        photos.sort(Comparator.comparingLong((Photo photo) -> photo.date).reversed());
        return photos;
    }

    private static InputStream open(Context context, Photo photo) throws IOException {
        try {
            InputStream input = context.getContentResolver().openInputStream(photo.uri);
            if (input != null) return input;
        } catch (FileNotFoundException | SecurityException error) {
            if (photo.localCopy == null) throw error;
        }
        if (photo.localCopy != null) return new FileInputStream(photo.localCopy);
        throw new FileNotFoundException(photo.uri.toString());
    }

    static Bitmap decode(Context context, Photo photo, int pixels) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = open(context, photo)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > pixels) {
            options.inSampleSize *= 2;
        }
        try (InputStream input = open(context, photo)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IOException(context.getString(R.string.photo_unreadable));
            return bitmap;
        }
    }

    private static Rect centerSquare(int width, int height) {
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;
        return new Rect(left, top, left + side, top + side);
    }

    static Bitmap cornerThumbnail(Bitmap source, int pixels) {
        Rect crop = centerSquare(source.getWidth(), source.getHeight());
        int size = Math.min(crop.width(), pixels);
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(result).drawBitmap(source, crop, new Rect(0, 0, size, size),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static Bitmap decodeCorner(Context context, Photo photo, int pixels) throws IOException {
        try (InputStream input = open(context, photo)) {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(input, false);
            if (decoder == null) throw new IOException(context.getString(R.string.photo_unreadable));
            try {
                Rect crop = centerSquare(decoder.getWidth(), decoder.getHeight());
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (crop.width() / (options.inSampleSize * 2) >= pixels) options.inSampleSize *= 2;
                Bitmap sampled = decoder.decodeRegion(crop, options);
                if (sampled == null) throw new IOException(context.getString(R.string.photo_unreadable));
                if (sampled.getWidth() == sampled.getHeight() && sampled.getWidth() <= pixels) return sampled;
                try { return cornerThumbnail(sampled, pixels); }
                finally { sampled.recycle(); }
            } finally {
                decoder.recycle();
            }
        }
    }

    static Bitmap latestThumbnail(Context context, int pixels) {
        for (Photo photo : photos(context)) {
            try { return decodeCorner(context, photo, pixels); }
            catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                // Skip photos removed or made unavailable by the system.
            }
        }
        return null;
    }

    static void delete(Context context, Photo photo) throws IOException {
        if (MediaStore.AUTHORITY.equals(photo.uri.getAuthority())) {
            context.getContentResolver().delete(photo.uri, null, null);
        }
        if (photo.localCopy != null && photo.localCopy.exists() && !photo.localCopy.delete()) {
            throw new IOException(context.getString(R.string.delete_app_photo_failed));
        }
        index(context).edit().remove(photo.uri.toString()).apply();
    }
}
