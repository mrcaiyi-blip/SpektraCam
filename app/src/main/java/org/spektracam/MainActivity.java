package org.spektracam;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends LocalizedActivity {
    private static final int CAMERA_PERMISSION = 1, SAVE_PERMISSION = 2;
    private static final int PREVIEW_EDGE_DP = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService processing = Executors.newSingleThreadExecutor();
    private final ExecutorService galleryIndex = Executors.newSingleThreadExecutor();
    private CameraUi ui;
    private FrameLayout root, viewfinder;
    private final Rect safeInsets = new Rect();
    private int landscapeCutoutInset;
    private LinearLayout cameraRoot, filmButton;
    private TextureView texture;
    private View grid, focusMarker;
    private final Runnable hideFocusMarker = () -> {
        if (focusMarker != null) focusMarker.setVisibility(View.GONE);
    };
    private ImageView thumbnail;
    private ImageButton torchButton, switchButton, gridButton, timerButton, settingsButton;
    private TextView status, exposure;
    private Button shutter;
    private ZoomControlView zoomControl;
    private FilmChooserView chooser;
    private SharedPreferences preferences;
    private CameraManager manager;
    private List<FilmLook> films = java.util.Collections.emptyList();
    private FilmLook film;
    private List<RawCamera.Lens> allLenses = java.util.Collections.emptyList(), lenses = java.util.Collections.emptyList();
    private RawCamera.Lens lens;
    private RawCamera camera;
    private LutPreviewRenderer preview;
    private ZoomPolicy zoomPolicy = new ZoomPolicy(new float[]{1});
    private long photoRevision;
    private Uri latestCapture;
    private boolean galleryOpened;
    private boolean active, front, cameraReady, firstFrame, busy, torch;
    private int generation, countdown, compensation;
    private float zoom = 1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (countdown == 0) return;
            if (--countdown == 0) {
                takePhoto();
            } else {
                showStatus(Integer.toString(countdown));
                main.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("capture_settings", MODE_PRIVATE);
        manager = getSystemService(CameraManager.class);
        ui = new CameraUi(this);
        try {
            films = FilmLook.load(getAssets());
            String id = preferences.getString("selected_film", "kodak_portra_400");
            film = films.stream().filter(value -> value.id.equals(id)).findFirst().orElse(films.get(0))
                    .selectingPaper(preferences.getString("selected_print", null));
        } catch (Exception error) {
            setContentView(ui.text(getString(R.string.film_catalog_read_error), 15, ui.ink));
            return;
        }
        buildUi();
        FilmLook initialLook = film;
        String initialMeter = FilmSettingsActivity.meter(preferences);
        processing.execute(() -> {
            long start = android.os.SystemClock.elapsedRealtime();
            try {
                FilmCapture.warmUp(getApplicationContext(), initialLook, initialMeter);
                android.util.Log.i("SpektraPerf", "Film warm-up ms=" + (android.os.SystemClock.elapsedRealtime() - start));
            } catch (Exception | OutOfMemoryError | LinkageError error) {
                android.util.Log.w("SpektraPerf", "Film warm-up skipped; capture will initialize on demand", error);
            }
        });
        restoreLastPhoto();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        if (galleryOpened) {
            galleryOpened = false;
            restoreLastPhoto();
        }
        if (texture != null) startPreview();
    }

    @Override protected void onPause() {
        active = false;
        cancelCountdown();
        torch = false;
        closePreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        processing.shutdown();
        galleryIndex.shutdownNow();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (root != null) root.post(() -> {
            hideStatusBar();
            configureDeviceLayout();
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    private void configureDeviceLayout() {
        int degrees = displayRotationDegrees();
        boolean landscape = degrees == 90 || degrees == 270;
        // Use equal clearance on the short edges of the rotated camera panel.
        int crossInset = landscape ? landscapeCutoutInset : 0;
        int top = landscape ? crossInset : safeInsets.top;
        int bottom = landscape ? crossInset : safeInsets.bottom;
        if (root.getPaddingLeft() != safeInsets.left || root.getPaddingRight() != safeInsets.right
                || root.getPaddingTop() != top || root.getPaddingBottom() != bottom) {
            root.setPadding(safeInsets.left, top, safeInsets.right, bottom);
        }
        if (chooser != null) {
            chooser.setPadding(0, Math.max(0, safeInsets.top - top), 0, Math.max(0, safeInsets.bottom - bottom));
        }
        int availableWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int availableHeight = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        int width = landscape ? availableHeight : availableWidth;
        int height = landscape ? availableWidth : availableHeight;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cameraRoot.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            cameraRoot.setLayoutParams(params);
        }
        cameraRoot.setRotation(-degrees);
        zoomControl.setLabelRotation(degrees);
        thumbnail.setRotation(degrees);
        switchButton.setRotation(degrees);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        boolean allowed = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (code == CAMERA_PERMISSION) {
            if (allowed) startPreview();
            else showStatus(getString(R.string.camera_permission));
        } else if (code == SAVE_PERMISSION) {
            if (allowed) requestPhoto();
            else showStatus(getString(R.string.save_permission));
        }
    }

    private int dp(float value) { return ui.dp(value); }

    private void hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.statusBars());
        }
    }

    private void configureSystemBars() {
        View decor = getWindow().getDecorView();
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                int light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(ui.ink == 0xFF242822 ? light : 0, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
            if (ui.ink == 0xFF242822) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);
        root.setClipChildren(false);
        configureSystemBars();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
                safeInsets.set(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                safeInsets.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            // Navigation can overlay the landscape edge. Only hardware cutouts need extra clearance.
            landscapeCutoutInset = 0;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                landscapeCutoutInset = Math.max(insets.getDisplayCutout().getSafeInsetTop(),
                        insets.getDisplayCutout().getSafeInsetBottom());
            }
            configureDeviceLayout();
            return insets;
        });
        cameraRoot = new LinearLayout(this);
        cameraRoot.setOrientation(LinearLayout.VERTICAL);
        root.addView(cameraRoot, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), 0, dp(10), 0);
        switchButton = toolbarIcon(toolbar, "switch", getString(R.string.switch_camera), () -> {
            if (locked()) return;
            front = !front;
            compensation = 0;
            torch = false;
            chooseFacing();
            closePreview();
            startPreview();
        });
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        torchButton = toolbarIcon(toolbar, "flash", getString(R.string.torch), () -> {
            if (locked() || camera == null) return;
            torch = !torch;
            camera.setControls(compensation, torch);
            refreshControls();
        });
        timerButton = toolbarIcon(toolbar, "timer", getString(R.string.timer), () -> {
            if (locked()) return;
            int timer = preferences.getInt("capture_timer", 0);
            preferences.edit().putInt("capture_timer", timer == 0 ? 3 : timer == 3 ? 10 : 0).apply();
            refreshControls();
        });
        gridButton = toolbarIcon(toolbar, "grid", getString(R.string.grid), () -> {
            preferences.edit().putBoolean("capture_grid", !preferences.getBoolean("capture_grid", false)).apply();
            refreshControls();
        });
        settingsButton = toolbarIcon(toolbar, "settings", getString(R.string.settings), this::showSettings);
        cameraRoot.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        FrameLayout previewSlot = new FrameLayout(this);
        previewSlot.setPadding(dp(PREVIEW_EDGE_DP), 0, dp(PREVIEW_EDGE_DP), 0);
        cameraRoot.addView(previewSlot, new LinearLayout.LayoutParams(-1, 0, 1));
        viewfinder = new FrameLayout(this);
        viewfinder.setBackground(ui.shape(0xff000000, 5, 0));
        viewfinder.setClipToOutline(true);
        previewSlot.addView(viewfinder, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        previewSlot.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) -> {
            int availableWidth = Math.max(1, right - left - view.getPaddingLeft() - view.getPaddingRight());
            int availableHeight = Math.max(1, bottom - top);
            float ratio = 3f / 4;
            int width = Math.max(1, Math.min(availableWidth, Math.round(availableHeight * ratio)));
            int height = Math.max(1, Math.round(width / ratio));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) viewfinder.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width; params.height = height;
                viewfinder.setLayoutParams(params);
            }
        });
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { startPreview(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closePreview(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!locked()) {
                    hideFocusMarker.run();
                    setZoom(zoom * detector.getScaleFactor());
                }
                return true;
            }
        });
        GestureDetector focusTap = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                texture.performClick();
                focusPreview(event.getX(), event.getY());
                return true;
            }
        });
        texture.setOnTouchListener(new View.OnTouchListener() {
            private boolean singleFinger;

            @Override public boolean onTouch(View view, MotionEvent event) {
                pinch.onTouchEvent(event);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) singleFinger = true;
                if (action == MotionEvent.ACTION_POINTER_DOWN && singleFinger) {
                    singleFinger = false;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    focusTap.onTouchEvent(cancel);
                    cancel.recycle();
                }
                if (singleFinger) focusTap.onTouchEvent(event);
                return true;
            }
        });
        viewfinder.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        grid = new CameraUi.Grid(this);
        viewfinder.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        focusMarker = new View(this);
        focusMarker.setBackground(ui.shape(0x00000000, 4, 0xffffdc45));
        focusMarker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        focusMarker.setVisibility(View.GONE);
        viewfinder.addView(focusMarker, new FrameLayout.LayoutParams(dp(52), dp(52)));
        status = ui.text("", 12, 0xffffffff);
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        status.setBackground(ui.shape(0x99000000, 6, 0));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badge.topMargin = dp(8);
        viewfinder.addView(status, badge);

        FrameLayout settings = new FrameLayout(this);
        zoomControl = new ZoomControlView(this, new ZoomControlView.Listener() {
            @Override public void onZoom(float value) { if (!locked()) setZoom(value); }
            @Override public void onFinish() {}
            @Override public void onHeightChanged() {
                ViewGroupHeight.set(settings, zoomControl.preferredHeight());
            }
        });
        settings.addView(zoomControl, new FrameLayout.LayoutParams(-1, -1));
        exposure = ui.text("+0.0 EV", 12, ui.muted);
        exposure.setGravity(Gravity.CENTER);
        exposure.setContentDescription(getString(R.string.exposure_compensation));
        exposure.setOnClickListener(view -> showExposure());
        settings.addView(exposure, new FrameLayout.LayoutParams(dp(76), dp(44), Gravity.BOTTOM | Gravity.END));
        cameraRoot.addView(settings, new LinearLayout.LayoutParams(-1, zoomControl.preferredHeight()));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), 0, dp(8), 0);
        FrameLayout left = new FrameLayout(this), right = new FrameLayout(this);
        controls.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setImageDrawable(new CameraUi.Glyph("photo", ui.muted));
        thumbnail.setBackground(ui.shape(ui.panel, 8, ui.line));
        thumbnail.setPadding(dp(3), dp(3), dp(3), dp(3));
        thumbnail.setClipToOutline(true);
        thumbnail.setContentDescription(getString(R.string.latest_thumbnail));
        thumbnail.setOnClickListener(view -> openGallery(false));
        thumbnail.setOnLongClickListener(view -> { openGallery(true); return true; });
        left.addView(thumbnail, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        shutter = ui.button("");
        shutter.setContentDescription(getString(R.string.take_raw));
        GradientDrawable fill = new GradientDrawable(), ring = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL); fill.setColor(ui.ink); fill.setStroke(dp(5), ui.bg);
        ring.setShape(GradientDrawable.OVAL); ring.setColor(ui.bg); ring.setStroke(dp(1), ui.ink);
        LayerDrawable shutterGraphic = new LayerDrawable(new Drawable[]{ring, fill});
        shutterGraphic.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
        shutter.setBackground(shutterGraphic);
        shutter.setOnClickListener(view -> requestPhoto());
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(76), dp(76)));
        controls.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        filmButton = ui.cameraButton(film);
        filmButton.setOnClickListener(view -> showChooser());
        right.addView(filmButton, new FrameLayout.LayoutParams(dp(120), dp(64), Gravity.CENTER));
        LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, dp(96));
        footer.bottomMargin = dp(12);
        cameraRoot.addView(controls, footer);
        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> configureDeviceLayout());
        setContentView(root);
        showStatus(getString(R.string.starting_camera));
        refreshControls();
    }

    private static final class ViewGroupHeight {
        static void set(View view, int height) {
            if (view.getLayoutParams() == null) return;
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    private ImageButton toolbarIcon(LinearLayout toolbar, String icon, String label, Runnable action) {
        ImageButton button = ui.icon(icon, label);
        button.setOnClickListener(view -> action.run());
        toolbar.addView(button, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }

    private void startPreview() {
        if (!active || !texture.isAvailable() || preview != null ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (allLenses.isEmpty()) {
            try {
                allLenses = RawCamera.lenses(manager);
                if (allLenses.isEmpty()) {
                    showStatus(getString(R.string.no_raw_lens));
                    return;
                }
                front = allLenses.stream().noneMatch(value -> !value.front());
                chooseFacing();
            } catch (Exception error) {
                showStatus(getString(R.string.read_raw_lenses));
                return;
            }
        }
        if (lens == null) return;
        final int attempt = ++generation;
        cameraReady = firstFrame = false;
        final RawCamera next = new RawCamera(this, manager, lens, main, new RawCamera.Listener() {
            @Override public void ready() {
                if (generation != attempt) return;
                cameraReady = true;
                if (firstFrame && !busy) showStatus("");
                refreshControls();
            }
            @Override public void error(String message) { previewFailed(attempt, message); }
        });
        camera = next;
        preview = new LutPreviewRenderer(getAssets(), texture.getSurfaceTexture(),
                lens.previewSize.getWidth(), lens.previewSize.getHeight(),
                message -> main.post(() -> {
                    android.util.Log.w("SpektraCam", "Live preview failed: " + message);
                    previewFailed(attempt, getString(R.string.preview_failed));
                }),
                () -> main.post(() -> {
                    if (generation != attempt) return;
                    firstFrame = true;
                    if (cameraReady && !busy) showStatus("");
                    refreshControls();
                }));
        // The camera texture handles sensor orientation and the panel handles display rotation.
        preview.start(film.previewLut(),
                surface -> main.post(() -> {
                    if (active && generation == attempt) next.open(surface, compensation, torch);
                }));
        preview.setZoom(zoomPolicy.cropRatio(zoom));
        refreshControls();
    }

    private void previewFailed(int attempt, String message) {
        if (generation != attempt) return;
        closePreview();
        showStatus(message);
        refreshControls();
    }

    private void focusPreview(float x, float y) {
        if (locked() || !cameraReady || !firstFrame || camera == null || preview == null
                || texture.getWidth() == 0 || texture.getHeight() == 0) return;
        PointF target = preview.focusPoint(x / texture.getWidth(), y / texture.getHeight());
        if (target == null || !camera.focus(target)) return;
        focusMarker.setTranslationX(x - dp(26));
        focusMarker.setTranslationY(y - dp(26));
        focusMarker.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideFocusMarker);
        main.postDelayed(hideFocusMarker, 1250);
    }

    private void closePreview() {
        main.removeCallbacks(hideFocusMarker);
        hideFocusMarker.run();
        ++generation;
        cameraReady = firstFrame = false;
        if (camera != null) { camera.close(); camera = null; }
        if (preview != null) { preview.close(); preview = null; }
    }

    private void chooseFacing() {
        List<RawCamera.Lens> options = new ArrayList<>();
        for (RawCamera.Lens candidate : allLenses) if (candidate.front() == front) options.add(candidate);
        options.sort(Comparator.comparingDouble((RawCamera.Lens value) -> value.equivalent > 0 ? value.equivalent : 30)
                .thenComparing(value -> value.physicalId != null));
        if (options.isEmpty()) return;
        RawCamera.Lens primary = options.stream().min(Comparator.comparingDouble(value ->
                value.equivalent > 0 ? Math.abs(value.equivalent - 27) : 100)).orElse(options.get(0));
        float base = primary.equivalent > 0 ? primary.equivalent : 30;
        ArrayList<RawCamera.Lens> unique = new ArrayList<>();
        ArrayList<Float> ratios = new ArrayList<>();
        for (RawCamera.Lens option : options) {
            float ratio = option.equivalent > 0 ? option.equivalent / base : 30 / base;
            if (!ratios.isEmpty() && Math.abs(ratio - ratios.get(ratios.size() - 1)) < 0.05f) continue;
            unique.add(option);
            ratios.add(ratio);
        }
        float[] stops = new float[ratios.size()];
        for (int i = 0; i < stops.length; ++i) stops[i] = ratios.get(i);
        lenses = unique;
        zoomPolicy = new ZoomPolicy(stops);
        zoomControl.setPolicy(zoomPolicy, primary.equivalent);
        zoom = zoomPolicy.clamp(1);
        lens = lenses.get(zoomPolicy.lensIndex(zoom));
        zoomControl.update(zoom);
    }

    private void setZoom(float value) {
        if (lenses.isEmpty()) return;
        zoom = zoomPolicy.clamp(value);
        zoomControl.update(zoom);
        RawCamera.Lens target = lenses.get(zoomPolicy.lensIndex(zoom));
        if (target != lens) {
            lens = target;
            compensation = 0;
            torch = false;
            closePreview();
            startPreview();
        } else if (preview != null) {
            preview.setZoom(zoomPolicy.cropRatio(zoom));
        }
    }

    private int displayRotationDegrees() {
        int display = getWindowManager().getDefaultDisplay().getRotation();
        return display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180 : display == Surface.ROTATION_270 ? 270 : 0;
    }

    private int rotation() {
        if (lens == null) return 0;
        int degrees = displayRotationDegrees();
        return (lens.orientation + (front ? degrees : -degrees) + 360) % 360;
    }

    private boolean locked() { return busy || countdown > 0; }

    private void refreshControls() {
        shutter.setEnabled(countdown > 0 || !busy && cameraReady && firstFrame);
        shutter.setContentDescription(countdown > 0 ? getString(R.string.cancel_timer) : getString(R.string.take_raw));
        filmButton.setEnabled(!locked());
        settingsButton.setEnabled(!locked());
        zoomControl.setEnabled(!locked() && lens != null);
        switchButton.setEnabled(!locked() && allLenses.stream().anyMatch(value -> value.front() != front));
        torchButton.setEnabled(!locked() && cameraReady && lens != null && lens.torch);
        torchButton.setAlpha(torch ? 1 : .55f);
        boolean showGrid = preferences.getBoolean("capture_grid", false);
        grid.setVisibility(showGrid ? View.VISIBLE : View.GONE);
        gridButton.setAlpha(showGrid ? 1 : .55f);
        gridButton.setContentDescription(showGrid ? getString(R.string.grid_on) : getString(R.string.grid_off));
        int seconds = preferences.getInt("capture_timer", 0);
        timerButton.setAlpha(seconds == 0 ? .55f : 1);
        timerButton.setContentDescription(seconds == 0 ? getString(R.string.timer_off) : getString(R.string.timer_seconds, seconds));
        Range<Integer> range = exposureRange();
        exposure.setEnabled(!locked() && cameraReady && range != null && range.getLower() < range.getUpper());
        exposure.setText(String.format(Locale.ROOT, "%+.1f EV", compensation * exposureStep()));
    }

    private void showStatus(String value) {
        if (status == null) return;
        status.setText(value);
        status.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void requestPhoto() {
        if (countdown > 0) { cancelCountdown(); return; }
        if (!active || !cameraReady || !firstFrame || busy) return;
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, SAVE_PERMISSION);
            return;
        }
        int delay = preferences.getInt("capture_timer", 0);
        if (delay > 0) {
            countdown = delay;
            showStatus(Integer.toString(delay));
            main.postDelayed(tick, 1000);
            refreshControls();
        } else takePhoto();
    }

    private void cancelCountdown() {
        if (countdown == 0) return;
        countdown = 0;
        main.removeCallbacks(tick);
        showStatus("");
        refreshControls();
    }

    private void takePhoto() {
        if (!active || camera == null || !cameraReady || busy) { refreshControls(); return; }
        busy = true;
        refreshControls();
        showStatus(getString(R.string.developing_raw));
        FilmLook selected = film;
        String meter = FilmSettingsActivity.meter(preferences);
        RawCamera.Lens capturedLens = lens;
        float crop = zoomPolicy.cropRatio(zoom);
        int degrees = rotation();
        long shutterTime = android.os.SystemClock.elapsedRealtime();
        camera.capture((raw, metadata, error) -> {
            if (error != null) { complete(null, null, error); return; }
            long rawReady = android.os.SystemClock.elapsedRealtime();
            processing.execute(() -> {
                Bitmap photo = null;
                try {
                    long renderStart = android.os.SystemClock.elapsedRealtime();
                    photo = FilmCapture.render(getApplicationContext(), capturedLens, raw, metadata, selected, meter, crop, degrees);
                    long rendered = android.os.SystemClock.elapsedRealtime();
                    Uri uri = save(photo);
                    CaptureGalleryStore.Photo entry = CaptureGalleryStore.remember(this, uri, null, selected.title,
                            selected.paper.isEmpty() ? "direct_scan" : selected.finishTitle);
                    Bitmap small = CaptureGalleryStore.cornerThumbnail(photo, 320);
                    try {
                        CaptureGalleryStore.cacheLatestPreview(entry, photo);
                    } catch (RuntimeException | OutOfMemoryError previewError) {
                        android.util.Log.w("SpektraPerf", "Could not cache the latest photo preview", previewError);
                    }
                    android.util.Log.i("SpektraPerf", "Shot ms: camera=" + (rawReady - shutterTime)
                            + " queue=" + (renderStart - rawReady) + " render=" + (rendered - renderStart)
                            + " save+previews=" + (android.os.SystemClock.elapsedRealtime() - rendered));
                    main.post(() -> complete(uri, small, getString(R.string.photo_saved)));
                } catch (Exception | OutOfMemoryError failure) {
                    String message = getString(R.string.develop_failed);
                    main.post(() -> complete(null, null, message));
                } finally {
                    if (photo != null) photo.recycle();
                }
            });
        });
    }

    private Uri save(Bitmap photo) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "SpektraCam_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/SpektraCam");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException(getString(R.string.create_gallery_photo));
        boolean saved = false;
        try {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null || !photo.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                    throw new IOException(getString(R.string.write_jpeg));
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues visible = new ContentValues();
                visible.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(uri, visible, null, null) != 1) throw new IOException(getString(R.string.publish_photo));
            }
            saved = true;
            return uri;
        } finally {
            if (!saved) getContentResolver().delete(uri, null, null);
        }
    }

    private void complete(Uri uri, Bitmap small, String message) {
        busy = false;
        if (isDestroyed()) { if (small != null) small.recycle(); return; }
        if (uri != null && small != null) {
            ++photoRevision;
            latestCapture = uri;
            thumbnail.setImageBitmap(small);
        }
        showStatus(message);
        refreshControls();
        if (uri != null) main.postDelayed(() -> {
            if (getString(R.string.photo_saved).contentEquals(status.getText())) showStatus("");
        }, 2000);
    }

    private void openGallery(boolean latest) {
        if (locked()) return;
        galleryOpened = true;
        Intent gallery = new Intent(this, CaptureGalleryActivity.class)
                .putExtra(CaptureGalleryActivity.OPEN_LATEST, latest);
        if (latest && latestCapture != null) gallery.setData(latestCapture);
        startActivity(gallery);
    }

    private void restoreLastPhoto() {
        final long revision = photoRevision;
        galleryIndex.execute(() -> {
            CaptureGalleryStore.LatestPreview thumbnailEntry = CaptureGalleryStore.latestThumbnail(this, 320);
            Bitmap thumbnailBitmap = thumbnailEntry == null ? null : thumbnailEntry.bitmap;
            main.post(() -> {
                if (isDestroyed() || revision != photoRevision) {
                    if (thumbnailBitmap != null) thumbnailBitmap.recycle();
                    return;
                }
                latestCapture = thumbnailEntry == null ? null : thumbnailEntry.photo.uri;
                if (thumbnailBitmap == null) thumbnail.setImageDrawable(new CameraUi.Glyph("photo", ui.muted));
                else thumbnail.setImageBitmap(thumbnailBitmap);
            });
            if (thumbnailEntry != null) CaptureGalleryStore.warmDetail(this, thumbnailEntry.photo);
        });
    }

    private void showChooser() {
        if (locked() || chooser != null) return;
        chooser = new FilmChooserView(this, films, film, selected -> {
            film = selected;
            preferences.edit().putString("selected_film", selected.id)
                    .putString("selected_print", selected.paper).apply();
            ui.updateCameraButton(filmButton, film);
            if (preview != null) preview.setLut(film.previewLut());
            closeChooser();
        });
        root.addView(chooser, new FrameLayout.LayoutParams(-1, -1));
        configureDeviceLayout();
        cameraRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        chooser.requestFocus();
    }

    private void closeChooser() {
        if (chooser == null) return;
        root.removeView(chooser);
        chooser = null;
        cameraRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    @Override public void onBackPressed() {
        if (chooser != null) closeChooser();
        else if (countdown > 0) cancelCountdown();
        else super.onBackPressed();
    }

    private void showSettings() {
        if (!locked()) startActivity(new Intent(this, FilmSettingsActivity.class));
    }

    private Range<Integer> exposureRange() {
        return lens == null ? null : lens.controls.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
    }

    private float exposureStep() {
        Rational step = lens == null ? null : lens.controls.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return step == null ? 0 : step.floatValue();
    }

    private void showExposure() {
        Range<Integer> range = exposureRange();
        if (locked() || range == null || !exposure.isEnabled()) return;
        LinearLayout body = new LinearLayout(this);
        body.setPadding(dp(18), dp(10), dp(18), 0);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView amount = ui.text(exposure.getText().toString(), 16, ui.ink);
        SeekBar slider = new SeekBar(this);
        slider.setMin(range.getLower());
        slider.setMax(range.getUpper());
        slider.setProgress(compensation);
        body.addView(amount); body.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                if (!fromUser || camera == null) return;
                setExposure(value);
                amount.setText(exposure.getText());
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        new AlertDialog.Builder(this).setTitle(getString(R.string.exposure_compensation)).setView(body)
                .setNeutralButton(getString(R.string.reset), (dialog, choice) -> setExposure(0))
                .setPositiveButton(getString(R.string.done), null).show();
    }

    private void setExposure(int value) {
        compensation = value;
        if (camera != null) camera.setControls(compensation, torch);
        refreshControls();
    }
}
