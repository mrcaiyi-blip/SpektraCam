package org.spektracam;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/** A finishing step shared by the picker sample and the saved JPEG. */
enum FilmBorder {
    NONE("none", R.string.film_border_none),
    PRINT("print", R.string.film_border_print),
    STRIP("strip", R.string.film_border_strip),
    INSTANT("instant", R.string.film_border_instant);

    static final String PREFERENCE_KEY = "selected_film_border";
    static final String TEXT_MODE_KEY = "film_border_text_mode";
    static final String CUSTOM_TEXT_KEY = "film_border_custom_text";
    private static final int PAPER = 0xFFF9F6EE;
    private static final int NEGATIVE = 0xFF251E1B;
    private static final int PERFORATION = 0xFFEEEBE3;
    private static final int EDGE_INK = 0xFFE6AC74;
    private static final int PAPER_INK = 0xFF302A24;
    private static final int SWATCH = 0xFF687B62;
    private static final int SWATCH_PAPER_OUTLINE = 0xFFD2CFC4;

    final String id;
    final int label;

    FilmBorder(String id, int label) {
        this.id = id;
        this.label = label;
    }

    static FilmBorder fromId(String id) {
        for (FilmBorder border : values()) {
            if (border.id.equals(id)) return border;
        }
        return null;
    }

    enum TextMode {
        DEFAULT("default", R.string.film_border_text_default),
        HIDDEN("hidden", R.string.film_border_text_hidden),
        FILM("film", R.string.film_border_text_film),
        CUSTOM("custom", R.string.film_border_text_custom);

        final String id;
        final int label;

        TextMode(String id, int label) {
            this.id = id;
            this.label = label;
        }
    }

    record Caption(String label, boolean frameNumber) {
        static final Caption EMPTY = new Caption("", false);
    }

    record TextSettings(TextMode mode, String custom) {
        static TextSettings read(SharedPreferences preferences) {
            String id = preferences.getString(TEXT_MODE_KEY, "");
            String custom = preferences.getString(CUSTOM_TEXT_KEY, "");
            for (TextMode mode : TextMode.values()) {
                if (mode.id.equals(id)) return new TextSettings(mode, custom);
            }
            return new TextSettings(TextMode.DEFAULT, custom);
        }

        Caption caption(FilmBorder border, String film) {
            String label = switch (mode) {
                case DEFAULT -> border == STRIP ? "135" : "";
                case HIDDEN -> "";
                case FILM -> film;
                case CUSTOM -> custom;
            };
            label = label.replaceAll("[\\s\\p{Z}]+", " ").trim();
            return label.isEmpty() || border == NONE ? Caption.EMPTY : new Caption(label, border == STRIP);
        }
    }

    Bitmap preview(Bitmap source, Caption caption) {
        return this == NONE ? source : render(source, new Rect(0, 0, source.getWidth(), source.getHeight()), 0, caption);
    }

    Bitmap render(Bitmap source, Rect crop, int rotation, Caption caption) {
        int degrees = (rotation + 360) % 360;
        boolean swaps = degrees == 90 || degrees == 270;
        int width = swaps ? crop.height() : crop.width();
        int height = swaps ? crop.width() : crop.height();
        Layout layout = layout(width, height);
        Bitmap image = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888);
        image.setHasAlpha(false);
        Canvas canvas = new Canvas(image);
        drawBackground(canvas, layout, caption);
        canvas.translate(layout.side, layout.top);
        // Move the rotated source rectangle back into the positive quadrant.
        canvas.translate(degrees == 90 || degrees == 180 ? width : 0,
                degrees == 180 || degrees == 270 ? height : 0);
        canvas.rotate(degrees);
        canvas.drawBitmap(source, crop, new Rect(0, 0, crop.width(), crop.height()), null);
        return image;
    }

    void drawSwatch(Canvas canvas, float width, float height) {
        Layout layout = layout(this == INSTANT ? 90 : 135, 90);
        float scale = Math.min(width / layout.width, height / layout.height);
        canvas.save();
        canvas.translate((width - layout.width * scale) / 2, (height - layout.height * scale) / 2);
        canvas.scale(scale, scale);
        drawBackground(canvas, layout, Caption.EMPTY);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(SWATCH);
        canvas.drawRect(layout.side, layout.top, layout.width - layout.side, layout.height - layout.bottom, paint);
        if (this == PRINT || this == INSTANT) {
            float stroke = 0.65f / scale;
            paint.setColor(SWATCH_PAPER_OUTLINE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawRect(stroke / 2, stroke / 2, layout.width - stroke / 2, layout.height - stroke / 2, paint);
        }
        canvas.restore();
    }

    private Layout layout(int photoWidth, int photoHeight) {
        int shortEdge = Math.min(photoWidth, photoHeight);
        boolean portrait = photoWidth < photoHeight;
        double mm = shortEdge / 24.0;
        int margin = (int) Math.round(shortEdge * 0.05);
        int gap = (int) Math.round(mm * 2);
        int rebate = (int) Math.round(mm * 5.5);
        int side = this == STRIP ? (portrait ? rebate : gap) : this == NONE ? 0 : margin;
        int top = this == STRIP ? (portrait ? gap : rebate) : side;
        int bottom = this == INSTANT ? (int) Math.round(shortEdge * 0.14) : top;
        return new Layout(photoWidth + 2 * side, photoHeight + top + bottom, side, top, bottom, portrait, mm, gap);
    }

    private void drawBackground(Canvas canvas, Layout layout, Caption caption) {
        if (this == NONE) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(this == STRIP ? NEGATIVE : PAPER);
        canvas.drawRect(0, 0, layout.width, layout.height, paint);
        if (this != STRIP) {
            float size = Math.min(layout.bottom * 0.58f, (float) (layout.mm * 0.9));
            if (size >= 1 && !caption.label.isEmpty()) {
                TextPaint text = captionPaint(PAPER_INK, size, Typeface.DEFAULT);
                String label = TextUtils.ellipsize(caption.label, text, layout.width - 2f * layout.side,
                        TextUtils.TruncateAt.END).toString();
                float center = layout.height - layout.bottom / 2f;
                text.setTextAlign(Paint.Align.CENTER);
                canvas.save();
                canvas.clipRect(layout.side, layout.height - layout.bottom, layout.width - layout.side, layout.height);
                canvas.drawText(label, layout.width / 2f, center - (text.ascent() + text.descent()) / 2, text);
                canvas.restore();
            }
            return;
        }

        int length = layout.portrait ? layout.height : layout.width;
        int breadth = layout.portrait ? layout.width : layout.height;
        float holeWidth = (float) (1.98 * layout.mm), holeHeight = (float) (2.79 * layout.mm);
        float pitch = (float) (4.75 * layout.mm), outer = (float) (1.7 * layout.mm);
        float radius = (float) (0.5 * layout.mm);
        int count = (int) Math.floor((length - 2 * layout.gap - holeWidth) / pitch) + 1;
        float start = (length - (count - 1) * pitch - holeWidth) / 2;

        canvas.save();
        if (layout.portrait) {
            canvas.translate(layout.width, 0);
            canvas.rotate(90);
        }
        paint.setColor(PERFORATION);
        for (int index = 0; index < count; index++) {
            float x = start + index * pitch;
            canvas.drawRoundRect(x, outer, x + holeWidth, outer + holeHeight, radius, radius, paint);
            canvas.drawRoundRect(x, breadth - outer - holeHeight, x + holeWidth, breadth - outer, radius, radius, paint);
        }
        if (caption.frameNumber) {
            TextPaint text = captionPaint(EDGE_INK, Math.max(1, Math.round(0.9 * layout.mm)), Typeface.MONOSPACE);
            float baseline = breadth - outer * 0.25f;
            float available = length - 3f * layout.gap - text.measureText("24A");
            if (available > 0) {
                canvas.clipRect(0, breadth - outer, length, breadth);
                String label = TextUtils.ellipsize(caption.label, text, available, TextUtils.TruncateAt.END).toString();
                canvas.drawText(label, layout.gap, baseline, text);
                text.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("24A", length - layout.gap, baseline, text);
            }
        }
        canvas.restore();
    }

    private static TextPaint captionPaint(int color, float size, Typeface typeface) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        return paint;
    }

    private record Layout(int width, int height, int side, int top, int bottom, boolean portrait,
                          double mm, int gap) {}
}
