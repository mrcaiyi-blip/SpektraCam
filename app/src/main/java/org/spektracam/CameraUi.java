package org.spektracam;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Native UI primitives; the capture pipeline and camera device stay in MainActivity. */
final class CameraUi {
  final Context context;
  final int bg, panel, ink, muted, line, soft, accent;
  CameraUi(Context context) {
    this.context = context;
    boolean dark =
        (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    bg = dark ? 0xFF1E201F : 0xFFF0EEE8;
    panel = dark ? 0xFF282B27 : 0xFFFAF9F5;
    ink = dark ? 0xFFECEEE6 : 0xFF242822;
    muted = dark ? 0xFFA5AA9E : 0xFF75786E;
    line = dark ? 0xFF41463C : 0xFFDCDCD4;
    soft = dark ? 0xFF383C34 : 0xFFE7E6DF;
    accent = dark ? 0xFFDFA56E : 0xFFC37637;
  }

  int dp(float x) {
    return Math.round(x * context.getResources().getDisplayMetrics().density);
  }

  GradientDrawable shape(int color, int radius, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("tnum");
    return t;
  }

  Button button(String value) {
    Button b = new Button(context);
    b.setText(value);
    b.setTextSize(12);
    b.setTextColor(ink);
    b.setAllCaps(false);
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp(10), 0, dp(10), 0);
    b.setBackground(shape(soft, 9, 0));
    return b;
  }

  ImageButton icon(String name, String label) {
    ImageButton b = new ImageButton(context);
    b.setImageDrawable(new Glyph(name, ink));
    b.setContentDescription(label);
    b.setPadding(dp(12), dp(12), dp(12), dp(12));
    b.setBackgroundResource(android.R.drawable.list_selector_background);
    return b;
  }

  LinearLayout cameraButton(FilmLook film) {
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setFocusable(true);
    box.setPadding(dp(7), dp(6), dp(7), dp(6));
    box.setBackground(shape(soft, 11, 0));
    box.setGravity(Gravity.CENTER_VERTICAL);
    updateCameraButton(box, film);
    return box;
  }

  void updateCameraButton(LinearLayout box, FilmLook film) {
    box.removeAllViews();
    LinearLayout top = new LinearLayout(context);
    top.setGravity(Gravity.CENTER_VERTICAL);
    ImageView icon = new ImageView(context);
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setImageDrawable(new Glyph("film", ink));
    top.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(27)));
    TextView name = text(film.title, 12, ink);
    name.setSingleLine(true);
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
    np.leftMargin = dp(7);
    top.addView(name, np);
    box.addView(top, new LinearLayout.LayoutParams(-1, dp(29)));
    TextView caption = text(film.finishTitle(context), 11, muted);
    caption.setMaxLines(1);
    caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
    box.addView(caption);
    box.setContentDescription(context.getString(R.string.choose_current_look, film.title, film.finishTitle(context)));
    box.setTag("camera-selection-button");
  }

  static final class Glyph extends Drawable {
    final String name;
    final Paint p = new Paint(3);

    Glyph(String name, int color) {
      this.name = name;
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.5f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
    }

    void l(Canvas c, float a, float b, float x, float y) {
      c.drawLine(a, b, x, y, p);
    }

    @Override
    public void draw(Canvas canvas) {
      Rect b = getBounds();
      canvas.save();
      canvas.translate(b.left, b.top);
      canvas.scale(b.width() / 24f, b.height() / 24f);
      switch (name) {
        case "film":
          canvas.drawRoundRect(4, 5, 20, 19, 2, 2, p);
          l(canvas, 8, 5, 8, 19); l(canvas, 16, 5, 16, 19);
          l(canvas, 4, 10, 8, 10); l(canvas, 4, 14, 8, 14);
          l(canvas, 16, 10, 20, 10); l(canvas, 16, 14, 20, 14);
          break;
        case "grid":
          for (int i = 1; i < 3; i++) {
            l(canvas, 3 + i * 6, 3, 3 + i * 6, 21);
            l(canvas, 3, 3 + i * 6, 21, 3 + i * 6);
          }
          canvas.drawRect(3, 3, 21, 21, p);
          break;
        case "timer":
          canvas.drawCircle(12, 13, 8, p);
          l(canvas, 10, 2, 14, 2);
          l(canvas, 12, 13, 12, 8);
          l(canvas, 12, 13, 16, 15);
          break;
        case "settings":
          for (int y : new int[] {6, 12, 18}) l(canvas, 3, y, 21, y);
          l(canvas, 8, 3, 8, 9);
          l(canvas, 16, 9, 16, 15);
          l(canvas, 10, 15, 10, 21);
          break;
        case "flash":
          Path path = new Path();
          path.moveTo(13, 2);
          path.lineTo(5, 13);
          path.lineTo(11, 13);
          path.lineTo(10, 22);
          path.lineTo(20, 10);
          path.lineTo(13, 10);
          path.close();
          canvas.drawPath(path, p);
          break;
        case "photo":
          canvas.drawRoundRect(3, 3, 21, 21, 3, 3, p);
          canvas.drawCircle(8, 8, 2, p);
          l(canvas, 3, 18, 10, 11);
          l(canvas, 10, 11, 14, 15);
          l(canvas, 14, 15, 17, 12);
          l(canvas, 17, 12, 21, 16);
          break;
        case "back":
          l(canvas, 14, 5, 7, 12);
          l(canvas, 7, 12, 14, 19);
          break;
        case "return":
          l(canvas, 5, 5, 18, 18);
          l(canvas, 18, 18, 18, 9);
          l(canvas, 18, 18, 9, 18);
          break;
        case "switch":
          canvas.drawRoundRect(3, 6, 21, 19, 3, 3, p);
          l(canvas, 7, 11, 14, 11);
          l(canvas, 14, 11, 12, 9);
          l(canvas, 17, 15, 10, 15);
          l(canvas, 10, 15, 12, 17);
          break;
        default:
          canvas.drawRoundRect(3, 6, 21, 20, 3, 3, p);
          canvas.drawCircle(12, 13, 4, p);
          l(canvas, 8, 6, 9, 3);
          l(canvas, 9, 3, 15, 3);
          l(canvas, 15, 3, 16, 6);
      }
      canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
      p.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter f) {
      p.setColorFilter(f);
      invalidateSelf();
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static final class Grid extends View {
    final Paint p = new Paint(3);

    Grid(Context c) {
      super(c);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      p.setColor(0x55FFFFFF);
      p.setStrokeWidth(c.getResources().getDisplayMetrics().density * .7f);
      setClickable(false);
    }

    @Override
    protected void onDraw(Canvas c) {
      for (int i = 1; i < 3; i++) {
        c.drawLine(getWidth() * i / 3f, 0, getWidth() * i / 3f, getHeight(), p);
        c.drawLine(0, getHeight() * i / 3f, getWidth(), getHeight() * i / 3f, p);
      }
    }
  }
}
