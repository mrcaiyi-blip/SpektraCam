package org.spektracam;

import android.content.Context;
import android.graphics.BitmapFactory;
import java.io.IOException;
import java.io.InputStream;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Full-screen route. Film state stays pending until the bottom-right return. */
final class FilmChooserView extends LinearLayout {
  interface Listener {
    void selected(FilmLook film);
  }

  private final CameraUi ui;
  private final List<FilmLook> cameras;
  private final Listener listener;
  private FilmLook film;
  private int category = R.string.all_films;
  private final LinearLayout info, filters, strip, printRow, printStrip;
  private final ImageView sample;
  private final TextView sampleTitle;
  private final HorizontalScrollView cameraScroll;
  private final HorizontalScrollView printScroll;
  private final TextView printLabel;

  FilmChooserView(
      Context c,
      List<FilmLook> cameras,
      FilmLook selected,
      Listener listener) {
    super(c);
    this.ui = new CameraUi(c);
    this.cameras = cameras;
    this.film = selected;
    this.listener = listener;
    setOrientation(VERTICAL);
    setBackgroundColor(ui.panel);
    setContentDescription(getContext().getString(R.string.choose_film_title));
    setTag("camera-chooser-page");
    setClickable(true);
    setFocusable(true);
    info = new LinearLayout(c);
    info.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(10));
    info.setGravity(Gravity.CENTER_VERTICAL);
    addView(info, new LayoutParams(-1, ui.dp(85)));
    printRow = new LinearLayout(c);
    printRow.setGravity(Gravity.CENTER_VERTICAL);
    printRow.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    printLabel = ui.text("", 13, ui.muted);
    LayoutParams label = new LayoutParams(-2, -2);
    label.rightMargin = ui.dp(10);
    printRow.addView(printLabel, label);
    printScroll = new HorizontalScrollView(c);
    printScroll.setHorizontalScrollBarEnabled(false);
    printStrip = new LinearLayout(c);
    printStrip.setGravity(Gravity.CENTER_VERTICAL);
    printScroll.addView(printStrip, new FrameLayout.LayoutParams(-2, -1));
    printRow.addView(printScroll, new LayoutParams(0, -1, 1));
    addView(printRow, new LayoutParams(-1, ui.dp(44)));
    LinearLayout sampleArea = new LinearLayout(c);
    sampleArea.setOrientation(VERTICAL);
    sampleArea.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
    addView(sampleArea, new LayoutParams(-1, 0, 1));
    sample = new ImageView(c);
    sample.setScaleType(ImageView.ScaleType.FIT_CENTER);
    sample.setBackground(ui.shape(ui.bg, 7, 0));
    sample.setClipToOutline(true);
    sampleArea.addView(sample, new LayoutParams(-1, 0, 1));
    sampleTitle = ui.text("", 11, ui.muted);
    sampleTitle.setGravity(Gravity.CENTER_VERTICAL);
    sampleArea.addView(sampleTitle, new LayoutParams(-1, ui.dp(30)));
    filters = new LinearLayout(c);
    filters.setGravity(Gravity.CENTER_VERTICAL);
    filters.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    addView(filters, new LayoutParams(-1, ui.dp(44)));
    cameraScroll = new HorizontalScrollView(c);
    cameraScroll.setHorizontalScrollBarEnabled(false);
    strip = new LinearLayout(c);
    strip.setPadding(ui.dp(20), 0, ui.dp(12), ui.dp(8));
    cameraScroll.addView(strip, new LayoutParams(-2, -1));
    addView(cameraScroll, new LayoutParams(-1, ui.dp(100)));
    LinearLayout footer = new LinearLayout(c);
    footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    footer.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    footer.setBackgroundColor(ui.panel);
    Button done = ui.button("↘  " + getContext().getString(R.string.camera));
    done.setTextColor(ui.panel);
    done.setBackground(ui.shape(ui.ink, 10, 0));
    done.setContentDescription(getContext().getString(R.string.return_to_camera));
    done.setTag("camera-chooser-return");
    done.setOnClickListener(v -> listener.selected(film));
    footer.addView(done, new LayoutParams(ui.dp(106), ui.dp(44)));
    addView(footer, new LayoutParams(-1, ui.dp(56)));
    drawDetails();
    drawPrints();
    drawFilters();
    drawCameras();
    cameraScroll.post(
        () -> {
          View selectedCard = strip.findViewWithTag("profile-" + film.id);
          if (selectedCard != null)
            cameraScroll.scrollTo(
                Math.max(
                    0,
                    selectedCard.getLeft()
                        - (cameraScroll.getWidth() - selectedCard.getWidth()) / 2),
                0);
        });
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    boolean compact = h < ui.dp(500);
    int[] indices = {0, 1, 3, 4};
    int[] heights = {compact ? 60 : 85, compact ? 36 : 44, compact ? 40 : 44, compact ? 86 : 100};
    for (int i = 0; i < indices.length; i++) {
      View child = getChildAt(indices[i]);
      LayoutParams p = (LayoutParams) child.getLayoutParams();
      if (p.height != ui.dp(heights[i])) {
        p.height = ui.dp(heights[i]);
        child.setLayoutParams(p);
      }
    }
  }

  private void drawDetails() {
    info.removeAllViews();
    ImageView icon = new ImageView(getContext());
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setImageDrawable(new CameraUi.Glyph("film", ui.ink));
    info.addView(icon, new LayoutParams(ui.dp(66), ui.dp(48)));
    LinearLayout text = new LinearLayout(getContext());
    text.setOrientation(VERTICAL);
    LayoutParams p = new LayoutParams(0, -2, 1);
    p.leftMargin = ui.dp(14);
    info.addView(text, p);
    text.addView(ui.text(film.title, 20, ui.ink));
    if (film.prints.isEmpty()) text.addView(ui.text(film.finishTitle(getContext()), 12, ui.muted));
    try (InputStream image = getContext().getAssets().open(film.sampleAsset())) {
      sample.setImageBitmap(BitmapFactory.decodeStream(image));
    } catch (IOException error) {
      sample.setImageDrawable(new CameraUi.Glyph("film", ui.muted));
    }
    String caption = getContext().getString(R.string.sample_caption, film.title, film.finishTitle(getContext()));
    sample.setContentDescription(caption);
    sampleTitle.setText(caption);
  }

  private void drawPrints() {
    printStrip.removeAllViews();
    printRow.setVisibility(film.prints.isEmpty() ? GONE : VISIBLE);
    if (film.prints.isEmpty()) return;
    boolean cinema = film.category == R.string.motion_picture_negative;
    String label = getContext().getString(cinema ? R.string.print_film : R.string.print_paper);
    printLabel.setText(label);
    for (FilmLook.Print paper : film.prints) {
      TextView chip = ui.text(paper.title(), 13, ui.ink);
      chip.setSingleLine(true);
      chip.setGravity(Gravity.CENTER);
      chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
      chip.setTag(paper.id());
      chip.setContentDescription(getContext().getString(cinema ? R.string.choose_print : R.string.choose_paper, paper.title()));
      chip.setFocusable(true);
      chip.setOnClickListener(view -> {
        film = film.selectingPaper(paper.id());
        updatePrintSelection();
        drawDetails();
      });
      LayoutParams params = new LayoutParams(-2, ui.dp(32));
      params.rightMargin = ui.dp(8);
      printStrip.addView(chip, params);
    }
    updatePrintSelection();
    View selected = printStrip.findViewWithTag(film.paper);
    if (selected != null) printScroll.post(() -> printScroll.scrollTo(
        selected.getLeft() - (printScroll.getWidth() - selected.getWidth()) / 2, 0));
  }

  private void updatePrintSelection() {
    for (int i = 0; i < printStrip.getChildCount(); i++) {
      View chip = printStrip.getChildAt(i);
      boolean selected = film.paper.equals(chip.getTag());
      chip.setSelected(selected);
      chip.setBackground(ui.shape(selected ? ui.soft : ui.bg, 7, selected ? ui.ink : ui.line));
    }
  }

  private void drawFilters() {
    filters.removeAllViews();
    for (int value : new int[]{R.string.all_films, R.string.color_negative, R.string.color_reversal, R.string.motion_picture_negative}) {
      int label = value == R.string.color_negative ? R.string.negative_short
          : value == R.string.color_reversal ? R.string.reversal_short
          : value == R.string.motion_picture_negative ? R.string.cinema_short : value;
      Button b = ui.button(getContext().getString(label));
      b.setTextSize(12);
      b.setPadding(0, 0, 0, 0);
      b.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      b.setTextColor(category == value ? ui.ink : ui.muted);
      b.setSelected(category == value);
      b.setContentDescription(getContext().getString(R.string.film_category, getContext().getString(value)));
      filters.addView(b, new LayoutParams(0, ui.dp(44), 1));
      b.setOnClickListener(
          v -> {
            category = value;
            drawFilters();
            drawCameras();
            cameraScroll.scrollTo(0, 0);
          });
    }
  }

  private void drawCameras() {
    strip.removeAllViews();
    for (FilmLook entry : cameras) {
      if (category != R.string.all_films && category != entry.category) continue;
      LinearLayout choice = new LinearLayout(getContext());
      choice.setOrientation(VERTICAL);
      choice.setGravity(Gravity.CENTER);
      choice.setPadding(ui.dp(9), ui.dp(8), ui.dp(9), ui.dp(8));
      boolean selected = entry.id.equals(film.id);
      choice.setBackground(ui.shape(selected ? ui.soft : ui.bg, 9, selected ? ui.ink : 0));
      choice.setFocusable(true);
      choice.setContentDescription(getContext().getString(R.string.choose_film, entry.title));
      choice.setTag("profile-" + entry.id);
      choice.setSelected(selected);
      ImageView thumb = new ImageView(getContext());
      thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
      thumb.setImageDrawable(new CameraUi.Glyph("film", ui.ink));
      choice.addView(thumb, new LayoutParams(ui.dp(57), ui.dp(38)));
      TextView name = ui.text(entry.title, 12, ui.ink);
      name.setSingleLine(true);
      name.setGravity(Gravity.CENTER);
      choice.addView(name, new LayoutParams(-1, -2));
      LayoutParams p = new LayoutParams(ui.dp(102), -1);
      p.rightMargin = ui.dp(8);
      strip.addView(choice, p);
      choice.setOnClickListener(
          v -> {
            if (entry.id.equals(film.id)) return;
            int scroll = cameraScroll.getScrollX();
            film = entry;
            drawDetails();
            drawPrints();
            drawCameras();
            cameraScroll.post(() -> cameraScroll.scrollTo(scroll, 0));
          });
    }
  }
}
