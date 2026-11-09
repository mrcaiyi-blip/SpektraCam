package org.spektracam;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CaptureGalleryActivity extends LocalizedActivity {
    static final String OPEN_LATEST = "open_latest";
    private static final int PANEL = 0xe0222222;
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final ExecutorService detailImages = Executors.newFixedThreadPool(2);
    private final Map<CaptureGalleryStore.Photo, Bitmap> detailPreviews = new HashMap<>();
    private final Set<CaptureGalleryStore.Photo> detailPending = new HashSet<>();
    private Bitmap displayedPreview;
    private boolean detailFullRequested;
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private FrameLayout root;
    private List<CaptureGalleryStore.Photo> photos = Collections.emptyList();
    private CaptureGalleryStore.Photo selected;
    private boolean enteredLatest, deleting;
    private volatile int generation;
    private int tileSize, gridPosition, gridTop;
    private GridView grid;
    private final Rect safeArea = new Rect();
    private LinearLayout gridContent, toolbar;
    private TextView metadata, detailLoading;
    private GalleryZoomView detailImage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        enteredLatest = getIntent().getBooleanExtra(OPEN_LATEST, false);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            safeArea.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            layoutControls();
            return insets;
        });
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        systemBars(true);
        TextView loading = text(getString(R.string.loading_photos), 15);
        root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        images.execute(() -> {
            List<CaptureGalleryStore.Photo> entries = CaptureGalleryStore.photos(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                photos = entries;
                if (enteredLatest && !photos.isEmpty()) openPhoto(photos.get(0));
                else showGrid();
            });
        });
    }

    @Override protected void onDestroy() {
        ++generation;
        detailImages.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    private void systemBars(boolean visible) {
        int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int immersive = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(visible ? layout : layout | immersive);
        root.requestApplyInsets();
    }

    private void showGrid() {
        selected = null;
        ++generation;
        root.removeAllViews();
        systemBars(true);
        toolbar = null;
        metadata = null;
        detailLoading = null;
        detailImage = null;
        detailPreviews.clear();
        detailPending.clear();
        displayedPreview = null;
        LinearLayout content = new LinearLayout(this);
        gridContent = content;
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout heading = new FrameLayout(this);
        heading.addView(tool("‹", getString(R.string.back_to_camera), this::finish), new FrameLayout.LayoutParams(dp(56), -1, Gravity.START));
        TextView title = text(getString(R.string.gallery), 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(54)));

        if (photos.isEmpty()) {
            grid = null;
            TextView empty = text(getString(R.string.no_photos), 15);
            empty.setTextColor(0xffbdbdbd);
            content.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            tileSize = Math.max(1, (getResources().getDisplayMetrics().widthPixels - dp(6)) / 3);
            grid = new GridView(this);
            grid.setNumColumns(3);
            grid.setHorizontalSpacing(dp(3));
            grid.setVerticalSpacing(dp(3));
            grid.setPadding(0, 0, 0, dp(88));
            grid.setClipToPadding(false);
            grid.setAdapter(new PhotoAdapter());
            grid.setOnItemClickListener((parent, view, position, id) -> {
                gridPosition = grid.getFirstVisiblePosition();
                gridTop = grid.getChildCount() == 0 ? 0 : grid.getChildAt(0).getTop();
                openPhoto(photos.get(position));
            });
            content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
            grid.setSelectionFromTop(Math.min(gridPosition, photos.size() - 1), gridTop);
            GridView displayed = grid;
            displayed.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int nextSize = Math.max(1, (right - left - dp(6)) / 3);
                if (tileSize != nextSize) {
                    tileSize = nextSize;
                    ((BaseAdapter) displayed.getAdapter()).notifyDataSetChanged();
                }
            });
        }

        FrameLayout floating = new FrameLayout(this) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                View button = getChildAt(0);
                int x = (getWidth() - button.getMeasuredWidth()) / 2;
                int y = getHeight() * 5 / 6 - button.getMeasuredHeight() / 2;
                button.layout(x, y, x + button.getMeasuredWidth(), y + button.getMeasuredHeight());
            }
        };
        android.widget.Button shutter = new android.widget.Button(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.BLACK);
        ring.setStroke(dp(2), Color.WHITE);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.WHITE);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ring, disc});
        layers.setLayerInset(1, dp(7), dp(7), dp(7), dp(7));
        shutter.setBackground(layers);
        shutter.setContentDescription(getString(R.string.back_to_camera));
        shutter.setFocusable(true);
        shutter.setOnClickListener(view -> finish());
        floating.addView(shutter, new FrameLayout.LayoutParams(dp(64), dp(64)));
        root.addView(floating, new FrameLayout.LayoutParams(-1, -1));
        layoutControls();
    }

    private void openPhoto(CaptureGalleryStore.Photo photo) {
        gridContent = null;
        ++generation;
        root.removeAllViews();
        systemBars(true);
        detailImage = new GalleryZoomView(this, this::toggleTools, this::closePhoto, this::stepPhoto,
                this::requestFullSize);
        root.addView(detailImage, new FrameLayout.LayoutParams(-1, -1));
        detailLoading = text("", 15);
        root.addView(detailLoading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(PANEL);
        toolbar.addView(tool("‹", enteredLatest ? getString(R.string.back_to_camera) : getString(R.string.back_to_gallery), this::closePhoto),
                new LinearLayout.LayoutParams(dp(56), -1));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        toolbar.addView(tool(getString(R.string.share), getString(R.string.share_photo), () -> share(selected)), new LinearLayout.LayoutParams(dp(64), -1));
        toolbar.addView(tool(getString(R.string.delete), getString(R.string.delete_photo), () -> confirmDelete(selected)), new LinearLayout.LayoutParams(dp(64), -1));
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP));

        metadata = text("", 15);
        metadata.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        metadata.setPadding(dp(16), dp(12), dp(16), dp(12));
        metadata.setBackgroundColor(PANEL);
        root.addView(metadata, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        layoutControls();

        loadPhoto(photo);
    }

    private CaptureGalleryStore.Photo neighbor(int offset) {
        int current = photos.indexOf(selected);
        int next = current + offset;
        return current >= 0 && next >= 0 && next < photos.size() ? photos.get(next) : null;
    }

    private boolean stepPhoto(int offset) {
        if (deleting || selected == null) return false;
        CaptureGalleryStore.Photo next = neighbor(offset);
        if (next == null || !detailPreviews.containsKey(next)) return false;
        loadPhoto(next);
        return true;
    }

    private void loadPhoto(CaptureGalleryStore.Photo photo) {
        selected = photo;
        detailFullRequested = false;
        displayedPreview = detailPreviews.get(photo);
        detailImage.setPhoto(displayedPreview);
        metadata.setText(getString(R.string.photo_metadata, known(photo.primary), known(photo.secondary)));

        CaptureGalleryStore.Photo previous = neighbor(-1);
        CaptureGalleryStore.Photo next = neighbor(1);
        detailPreviews.keySet().removeIf(page -> page != photo && page != previous && page != next);
        refreshPreviews();
        preload(photo);
        preload(previous);
        preload(next);
    }

    private void preload(CaptureGalleryStore.Photo photo) {
        if (photo == null || detailPreviews.containsKey(photo) || !detailPending.add(photo)) return;
        int request = generation;
        detailImages.execute(() -> {
            if (request != generation) return;
            Bitmap bitmap = read(photo, 2048);
            if (bitmap != null) bitmap.prepareToDraw();
            runOnUiThread(() -> {
                if (request == generation) detailPending.remove(photo);
                if (isDestroyed() || isFinishing() || request != generation
                        || (photo != selected && photo != neighbor(-1) && photo != neighbor(1))) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                detailPreviews.put(photo, bitmap);
                refreshPreviews();
            });
        });
    }

    private void refreshPreviews() {
        Bitmap preview = detailPreviews.get(selected);
        if (preview != displayedPreview) {
            displayedPreview = preview;
            detailImage.setPreview(preview);
        }
        detailLoading.setVisibility(preview == null ? View.VISIBLE : View.GONE);
        detailLoading.setText(detailPreviews.containsKey(selected) ? getString(R.string.photo_unreadable) : getString(R.string.loading_photos));
        CaptureGalleryStore.Photo previous = neighbor(-1);
        CaptureGalleryStore.Photo next = neighbor(1);
        detailImage.setNeighbors(detailPreviews.get(previous), detailPreviews.containsKey(previous),
                detailPreviews.get(next), detailPreviews.containsKey(next));
    }

    private void requestFullSize() {
        if (detailFullRequested || selected == null) return;
        detailFullRequested = true;
        CaptureGalleryStore.Photo photo = selected;
        int request = generation;
        detailImages.execute(() -> {
            if (request != generation) return;
            Bitmap bitmap = read(photo, 4096);
            if (bitmap == null) return;
            bitmap.prepareToDraw();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || request != generation || selected != photo) {
                    bitmap.recycle();
                    return;
                }
                detailImage.setFullSizePhoto(bitmap);
            });
        });
    }

    private void layoutControls() {
        if (gridContent != null) {
            gridContent.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom);
        }
        if (toolbar != null && metadata != null) {
            toolbar.setPadding(safeArea.left, safeArea.top, safeArea.right, 0);
            ViewGroup.LayoutParams bar = toolbar.getLayoutParams();
            int height = dp(54) + safeArea.top;
            if (bar.height != height) {
                bar.height = height;
                toolbar.setLayoutParams(bar);
            }
            metadata.setPadding(dp(16) + safeArea.left, dp(12), dp(16) + safeArea.right, dp(12) + safeArea.bottom);
        }
    }

    private Bitmap read(CaptureGalleryStore.Photo photo, int pixels) {
        try { return CaptureGalleryStore.decode(this, photo, pixels); }
        catch (IOException | RuntimeException | OutOfMemoryError error) { return null; }
    }

    private void toggleTools() {
        if (deleting) return;
        boolean visible = toolbar.getVisibility() != View.VISIBLE;
        toolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        metadata.setVisibility(visible ? View.VISIBLE : View.GONE);
        systemBars(visible);
    }

    private void share(CaptureGalleryStore.Photo photo) {
        if (deleting) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, photo.uri);
        send.setClipData(ClipData.newUri(getContentResolver(), getString(R.string.photo), photo.uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(send, getString(R.string.share_photo))); }
        catch (RuntimeException error) { Toast.makeText(this, getString(R.string.share_failed), Toast.LENGTH_SHORT).show(); }
    }

    private void confirmDelete(CaptureGalleryStore.Photo photo) {
        if (deleting) return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.delete_photo_question))
                .setMessage(getString(R.string.delete_both_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete), (dialog, button) -> {
                    deleting = true;
                    images.execute(() -> {
                        try {
                            CaptureGalleryStore.delete(this, photo);
                            runOnUiThread(() -> {
                                if (isDestroyed() || isFinishing()) return;
                                deleting = false;
                                photos.remove(photo);
                                thumbnails.remove(photo.uri.toString());
                                closePhoto();
                            });
                        } catch (IOException | RuntimeException error) {
                            runOnUiThread(() -> {
                                deleting = false;
                                if (!isDestroyed() && !isFinishing()) {
                                    Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                }).show();
    }

    private void closePhoto() {
        if (deleting) return;
        if (enteredLatest) finish();
        else showGrid();
    }

    @Override public void onBackPressed() { back(); }

    private void back() {
        if (selected != null) closePhoto();
        else finish();
    }

    private String known(String value) {
        return switch (value) {
            case "" -> getString(R.string.unknown);
            case "direct_scan", "正片直扫", "Direct scan" -> getString(R.string.direct_scan);
            default -> value;
        };
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String label, int size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView tool(String label, String description, Runnable action) {
        TextView button = text(label, label.equals("‹") ? 32 : 15);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private final class PhotoAdapter extends BaseAdapter {
        @Override public int getCount() { return photos.size(); }
        @Override public Object getItem(int position) { return photos.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ImageView image = recycled instanceof ImageView ? (ImageView) recycled : new ImageView(CaptureGalleryActivity.this);
            image.setLayoutParams(new GridView.LayoutParams(-1, tileSize));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xff202020);
            CaptureGalleryStore.Photo photo = photos.get(position);
            String key = photo.uri.toString();
            image.setTag(key);
            image.setContentDescription(photo.primary.isEmpty() ? getString(R.string.view_photo) : photo.primary + ", " + known(photo.secondary));
            Bitmap cached = thumbnails.get(key);
            image.setImageBitmap(cached);
            if (cached == null) {
                int request = generation;
                images.execute(() -> {
                    if (isDestroyed() || request != generation) return;
                    Bitmap bitmap = read(photo, 480);
                    if (bitmap != null) thumbnails.put(key, bitmap);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && key.equals(image.getTag())) image.setImageBitmap(bitmap);
                    });
                });
            }
            return image;
        }
    }
}
