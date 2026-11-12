package org.spektracam;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.TypedValue;
import java.io.IOException;
import java.io.InputStream;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Full-screen route. The film, paper and border settings are applied together on return. */
final class FilmChooserView extends LinearLayout {
  interface Listener {
    void selected(FilmLook film, FilmBorder border, FilmBorder.TextSettings borderText);
  }

  private final CameraUi ui;
  private final List<FilmLook> cameras;
  private final Listener listener;
  private FilmBorder.TextSettings borderText;
  private FilmLook film;
  private FilmBorder border;
  private int category = R.string.all_films;
  private final LinearLayout info, filters, strip, printRow, printStrip, borderRow, borderStrip, borderTextRow, footer;
  private final Button borderTextButton;
  private final ImageView sample;
  private final TextView sampleTitle;
  private final HorizontalScrollView cameraScroll;
  private final HorizontalScrollView printScroll;
  private final TextView printLabel;
  private Bitmap sampleBitmap;
  private String sampleAsset;

  FilmChooserView(
      Context c,
      List<FilmLook> cameras,
      FilmLook selected,
      FilmBorder selectedBorder,
      FilmBorder.TextSettings borderText,
      Listener listener) {
    super(c);
    this.ui = new CameraUi(c);
    this.cameras = cameras;
    this.film = selected;
    this.border = selectedBorder;
    this.borderText = borderText;
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
    borderRow = new LinearLayout(c);
    borderRow.setGravity(Gravity.CENTER_VERTICAL);
    borderRow.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    LayoutParams borderLabelParams = new LayoutParams(-2, -2);
    borderLabelParams.rightMargin = ui.dp(10);
    borderRow.addView(ui.text(c.getString(R.string.film_border), 13, ui.muted), borderLabelParams);
    borderStrip = new LinearLayout(c);
    borderStrip.setGravity(Gravity.CENTER_VERTICAL);
    borderRow.addView(borderStrip, new LayoutParams(0, -1, 1));
    addView(borderRow, new LayoutParams(-1, ui.dp(52)));
    borderTextRow = new LinearLayout(c);
    borderTextRow.setGravity(Gravity.CENTER_VERTICAL);
    borderTextRow.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    borderTextRow.addView(ui.text(c.getString(R.string.film_border_text), 13, ui.muted),
        new LayoutParams(0, -2, 1));
    borderTextButton = ui.button("");
    borderTextButton.setBackground(ui.shape(ui.bg, 7, ui.line));
    borderTextButton.setOnClickListener(view -> showBorderTextMenu());
    borderTextRow.addView(borderTextButton, new LayoutParams(-2, ui.dp(32)));
    addView(borderTextRow, new LayoutParams(-1, ui.dp(44)));
    updateBorderTextButton();
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
    footer = new LinearLayout(c);
    footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    footer.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    footer.setBackgroundColor(ui.panel);
    Button done = ui.button("↘  " + getContext().getString(R.string.camera));
    done.setTextColor(ui.panel);
    done.setBackground(ui.shape(ui.ink, 10, 0));
    done.setContentDescription(getContext().getString(R.string.return_to_camera));
    done.setTag("camera-chooser-return");
    done.setOnClickListener(v -> listener.selected(film, border, this.borderText));
    footer.addView(done, new LayoutParams(ui.dp(106), ui.dp(44)));
    addView(footer, new LayoutParams(-1, ui.dp(56)));
    drawDetails();
    drawPrints();
    drawBorders();
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
    info.setPadding(ui.dp(20), ui.dp(compact ? 0 : 12), ui.dp(20), ui.dp(compact ? 0 : 10));
    setHeight(info, compact ? 48 : 85);
    setHeight(printRow, compact ? 36 : 44);
    setHeight(borderRow, compact ? 44 : 52);
    setHeight(borderTextRow, compact ? 36 : 44);
    setHeight(sampleTitle, compact ? 20 : 30);
    setHeight(filters, compact ? 36 : 44);
    setHeight(cameraScroll, compact ? 64 : 100);
    setHeight(footer, compact ? 44 : 56);
  }

  private void setHeight(View view, int height) {
    LayoutParams params = (LayoutParams) view.getLayoutParams();
    if (params.height != ui.dp(height)) {
      params.height = ui.dp(height);
      view.setLayoutParams(params);
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
    drawSample();
    String caption = getContext().getString(R.string.sample_caption, film.title, film.finishTitle(getContext()));
    sample.setContentDescription(caption);
    sampleTitle.setText(caption);
  }

  private void drawPrints() {
    printStrip.removeAllViews();
    boolean cinema = film.category == R.string.motion_picture_negative;
    String label = getContext().getString(cinema ? R.string.print_film : R.string.print_paper);
    printLabel.setText(label);
    if (film.prints.isEmpty()) {
      printStrip.addView(ui.text(film.finishTitle(getContext()), 13, ui.muted));
      printScroll.scrollTo(0, 0);
      return;
    }
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

  private void drawSample() {
    String asset = film.sampleAsset();
    if (!asset.equals(sampleAsset)) {
      sampleAsset = asset;
      try (InputStream image = getContext().getAssets().open(asset)) {
        sampleBitmap = BitmapFactory.decodeStream(image);
      } catch (IOException error) {
        sampleBitmap = null;
      }
    }
    if (sampleBitmap == null) sample.setImageDrawable(new CameraUi.Glyph("film", ui.muted));
    else sample.setImageBitmap(border.preview(sampleBitmap, borderText.caption(border, film.title)));
  }

  private void showBorderTextMenu() {
    PopupMenu popup = new PopupMenu(getContext(), borderTextButton, Gravity.END);
    for (FilmBorder.TextMode mode : FilmBorder.TextMode.values()) {
      popup.getMenu().add(0, mode.ordinal(), mode.ordinal(), mode.label)
          .setCheckable(true).setChecked(mode == borderText.mode());
    }
    popup.setOnMenuItemClickListener(item -> {
      FilmBorder.TextMode mode = FilmBorder.TextMode.values()[item.getItemId()];
      if (mode == FilmBorder.TextMode.CUSTOM) editBorderText();
      else selectBorderText(mode, borderText.custom());
      return true;
    });
    popup.show();
  }

  private void editBorderText() {
    Context context = getContext();
    EditText field = new EditText(context);
    field.setSingleLine(true);
    field.setHint(R.string.film_border_text_placeholder);
    field.setText(borderText.custom());
    field.setSelection(field.length());
    FrameLayout input = new FrameLayout(context);
    input.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    input.addView(field, new FrameLayout.LayoutParams(-1, -2));
    AlertDialog dialog = new AlertDialog.Builder(context)
        .setTitle(R.string.film_border_text)
        .setView(input)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.done, (d, button) ->
            selectBorderText(FilmBorder.TextMode.CUSTOM, field.getText().toString()))
        .create();
    field.requestFocus();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    dialog.show();
  }

  private void selectBorderText(FilmBorder.TextMode mode, String custom) {
    FilmBorder.TextSettings next = new FilmBorder.TextSettings(mode, custom);
    if (next.equals(borderText)) return;
    borderText = next;
    updateBorderTextButton();
    drawSample();
  }

  private void updateBorderTextButton() {
    String mode = getContext().getString(borderText.mode().label);
    borderTextButton.setText(mode + "  ▾");
    borderTextButton.setContentDescription(getContext().getString(R.string.film_border_text) + " · " + mode);
  }

  private void drawBorders() {
    for (FilmBorder option : FilmBorder.values()) {
      LinearLayout choice = new LinearLayout(getContext());
      choice.setOrientation(VERTICAL);
      choice.setGravity(Gravity.CENTER);
      choice.setTag("film-border-" + option.id);
      choice.setContentDescription(getContext().getString(option.label));
      choice.setFocusable(true);
      View swatch = new View(getContext()) {
        @Override protected void onDraw(Canvas canvas) {
          option.drawSwatch(canvas, getWidth(), getHeight());
        }
      };
      choice.addView(swatch, new LayoutParams(ui.dp(31), ui.dp(20)));
      TextView name = ui.text(getContext().getString(option.label), 11, ui.ink);
      name.setMaxLines(1);
      name.setGravity(Gravity.CENTER);
      name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      name.setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, TypedValue.COMPLEX_UNIT_SP);
      choice.addView(name, new LayoutParams(-1, ui.dp(17)));
      LayoutParams params = new LayoutParams(0, -1, 1);
      if (option != FilmBorder.NONE) params.leftMargin = ui.dp(5);
      params.topMargin = params.bottomMargin = ui.dp(1);
      borderStrip.addView(choice, params);
      choice.setOnClickListener(view -> {
        if (border == option) return;
        border = option;
        updateBorderSelection();
        drawSample();
      });
    }
    updateBorderSelection();
  }

  private void updateBorderSelection() {
    for (int index = 0; index < borderStrip.getChildCount(); index++) {
      View choice = borderStrip.getChildAt(index);
      boolean selected = ("film-border-" + border.id).equals(choice.getTag());
      choice.setSelected(selected);
      choice.setBackground(ui.shape(selected ? ui.soft : ui.bg, 8, selected ? ui.ink : ui.line));
    }
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
