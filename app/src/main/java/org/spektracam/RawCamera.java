package org.spektracam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Camera2 adapter for one RAW_SENSOR request per shutter press. */
final class RawCamera implements AutoCloseable {
    interface Listener {
        void ready();
        void error(String message);
    }

    static final class Lens {
        final String id, physicalId;
        final CameraCharacteristics info, controls;
        final Size rawSize, previewSize;
        final int facing, orientation;
        final float equivalent;
        final boolean torch;

        Lens(String id, String physicalId, CameraCharacteristics info, CameraCharacteristics controls,
                Size rawSize, Size previewSize) {
            this.id = id;
            this.physicalId = physicalId;
            this.info = info;
            this.controls = controls;
            this.rawSize = rawSize;
            this.previewSize = previewSize;
            facing = value(info.get(CameraCharacteristics.LENS_FACING), CameraCharacteristics.LENS_FACING_BACK);
            orientation = value(info.get(CameraCharacteristics.SENSOR_ORIENTATION), 90);
            torch = Boolean.TRUE.equals(controls.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            SizeF sensor = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            equivalent = sensor == null || focal == null || focal.length == 0 ? 0
                    : (float) (focal[0] * 43.27 / Math.hypot(sensor.getWidth(), sensor.getHeight()));
        }

        boolean front() { return facing == CameraCharacteristics.LENS_FACING_FRONT; }
    }

    static final class Frame {
        final ByteBuffer data;
        final long timestamp;
        final int width, height, rowStride, pixelStride;

        Frame(Image image) {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer source = plane.getBuffer().duplicate();
            data = ByteBuffer.allocateDirect(source.remaining());
            data.put(source).flip();
            timestamp = image.getTimestamp();
            width = image.getWidth();
            height = image.getHeight();
            rowStride = plane.getRowStride();
            pixelStride = plane.getPixelStride();
        }
    }

    static List<Lens> lenses(CameraManager manager) throws CameraAccessException {
        List<Lens> result = new ArrayList<>();
        List<String> publicIds = Arrays.asList(manager.getCameraIdList());
        for (String id : publicIds) {
            CameraCharacteristics logical = manager.getCameraCharacteristics(id);
            Lens direct = makeLens(id, null, logical, logical);
            if (direct != null) result.add(direct);
            if (Build.VERSION.SDK_INT >= 28) {
                for (String physicalId : logical.getPhysicalCameraIds()) {
                    if (publicIds.contains(physicalId)) continue;
                    try {
                        Lens physical = makeLens(id, physicalId, manager.getCameraCharacteristics(physicalId), logical);
                        if (physical != null) result.add(physical);
                    } catch (CameraAccessException | IllegalArgumentException ignored) {
                        // Some devices do not expose characteristics for every advertised physical ID.
                    }
                }
            }
        }
        return result;
    }

    private static Lens makeLens(String id, String physicalId, CameraCharacteristics info,
                                 CameraCharacteristics controls) {
        int[] caps = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        Integer cfa = info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        StreamConfigurationMap formats = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (caps == null || Arrays.stream(caps).noneMatch(cap -> cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                || cfa == null || cfa < 0 || cfa > 3 || formats == null) return null;
        Size[] raws = formats.getOutputSizes(ImageFormat.RAW_SENSOR);
        Size[] previews = formats.getOutputSizes(SurfaceTexture.class);
        if (raws == null || raws.length == 0 || previews == null || previews.length == 0) return null;
        Size raw = Arrays.stream(raws).filter(size -> pixels(size) <= 32L * 1024 * 1024)
                .max(Comparator.comparingLong(RawCamera::pixels)).orElse(null);
        if (raw == null) return null;
        Size preview = Arrays.stream(previews).min(Comparator
                .comparingDouble((Size size) -> Math.abs((double) size.getWidth() / size.getHeight()
                        - (double) raw.getWidth() / raw.getHeight()))
                .thenComparingLong(size -> Math.abs(pixels(size) - 1_440_000))).orElse(previews[0]);
        return new Lens(id, physicalId, info, controls, raw, preview);
    }

    private static long pixels(Size size) { return (long) size.getWidth() * size.getHeight(); }
    static int value(Integer value, int fallback) { return value == null ? fallback : value; }

    private final Context context;
    private final CameraManager manager;
    private final Lens lens;
    private final Handler handler;
    private final Listener listener;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface preview;
    private boolean closed, torch;
    private int compensation;
    private final boolean physicalFocus;
    private MeteringRectangle[] focusRegion;
    private Integer focusMode;
    private Rect previewCrop;
    private int focusRevision;
    private Pending pending;

    private final CameraCaptureSession.CaptureCallback previewMetadata = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            if (previewCrop != null) return;
            CaptureResult source = result;
            if (Build.VERSION.SDK_INT >= 28 && physicalFocus) {
                source = result.getPhysicalCameraResults().get(lens.physicalId);
            }
            if (source == null) return;
            Rect crop = source.get(CaptureResult.SCALER_CROP_REGION);
            if (crop != null && !crop.isEmpty()) previewCrop = crop;
        }
    };

    interface Shot {
        void result(Frame frame, CaptureResult metadata, String error);
    }

    private static final class Pending {
        final Shot callback;
        Frame frame;
        CaptureResult result;
        Long timestamp;
        Pending(Shot callback) { this.callback = callback; }
    }

    RawCamera(Context context, CameraManager manager, Lens lens, Handler handler, Listener listener) {
        this.context = context;
        this.manager = manager;
        this.lens = lens;
        this.handler = handler;
        this.listener = listener;
        List<CaptureRequest.Key<?>> physicalKeys = Build.VERSION.SDK_INT >= 28 && lens.physicalId != null
                ? lens.controls.getAvailablePhysicalCameraRequestKeys() : null;
        physicalFocus = physicalKeys != null && physicalKeys.containsAll(Arrays.asList(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_REGIONS));
    }

    @SuppressLint("MissingPermission")
    void open(Surface preview, int compensation, boolean torch) {
        this.preview = preview;
        this.compensation = compensation;
        this.torch = torch;
        try {
            reader = ImageReader.newInstance(lens.rawSize.getWidth(), lens.rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            manager.openCamera(lens.id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (closed) { camera.close(); return; }
                    device = camera;
                    configure();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    fail(context.getString(R.string.camera_disconnected));
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    fail(context.getString(R.string.raw_start_code, error));
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException error) {
            Log.w("SpektraCam", "Camera request failed", error);
            fail(null);
        }
    }

    private void configure() {
        try {
            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (closed) { configured.close(); return; }
                    session = configured;
                    setControls(compensation, torch);
                    if (!closed) listener.ready();
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    fail(context.getString(R.string.raw_preview_unsupported));
                }
            };
            if (Build.VERSION.SDK_INT >= 28 && lens.physicalId != null) {
                OutputConfiguration video = new OutputConfiguration(preview);
                OutputConfiguration raw = new OutputConfiguration(reader.getSurface());
                video.setPhysicalCameraId(lens.physicalId);
                raw.setPhysicalCameraId(lens.physicalId);
                device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(video, raw), handler::post, callback));
            } else {
                device.createCaptureSession(Arrays.asList(preview, reader.getSurface()), callback, handler);
            }
        } catch (CameraAccessException | RuntimeException error) {
            Log.w("SpektraCam", "Camera request failed", error);
            fail(null);
        }
    }

    private void applyControls(CaptureRequest.Builder request) {
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation);
        if (supportsFocusMode(lens.controls, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        if (focusRegion != null) {
            setFocusKey(request, CaptureRequest.CONTROL_AF_REGIONS, focusRegion);
            setFocusKey(request, CaptureRequest.CONTROL_AF_MODE, focusMode);
        }
        if (lens.torch) request.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    private static boolean supportsFocusMode(CameraCharacteristics info, int mode) {
        int[] modes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return modes != null && Arrays.stream(modes).anyMatch(value -> value == mode);
    }

    private <T> void setFocusKey(CaptureRequest.Builder request, CaptureRequest.Key<T> key, T value) {
        if (Build.VERSION.SDK_INT >= 28 && physicalFocus) {
            request.setPhysicalCameraKey(key, value, lens.physicalId);
        } else {
            request.set(key, value);
        }
    }

    private CaptureRequest.Builder previewRequest() throws CameraAccessException {
        CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        request.addTarget(preview);
        applyControls(request);
        return request;
    }

    boolean focus(PointF point) {
        if (closed || session == null || pending != null) return false;
        CameraCharacteristics info = physicalFocus ? lens.info : lens.controls;
        if (value(info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), 0) < 1
                || Float.valueOf(0f).equals(lens.info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))) return false;
        Rect crop = previewCrop != null ? previewCrop : info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (crop == null || crop.isEmpty()) return false;

        boolean auto = supportsFocusMode(info, CaptureRequest.CONTROL_AF_MODE_AUTO);
        boolean continuous = supportsFocusMode(info, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (!auto && !continuous) return false;

        MeteringRectangle[] previousRegion = focusRegion;
        Integer previousMode = focusMode;
        focusRegion = new MeteringRectangle[]{meteringRegion(point, crop)};
        focusMode = auto ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        try {
            CaptureRequest.Builder request = previewRequest();
            setFocusKey(request, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(request.build(), null, handler);
            setFocusKey(request, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(request.build(), null, handler);
            setFocusKey(request, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(request.build(), previewMetadata, handler);
            int revision = ++focusRevision;
            if (auto && continuous) handler.postDelayed(() -> resumeContinuousFocus(revision), 4000);
            return true;
        } catch (CameraAccessException | RuntimeException error) {
            focusRegion = previousRegion;
            focusMode = previousMode;
            Log.w("SpektraCam", "Could not start tap focus", error);
            return false;
        }
    }

    private MeteringRectangle meteringRegion(PointF point, Rect crop) {
        float streamAspect = (float) lens.previewSize.getWidth() / lens.previewSize.getHeight();
        float width = Math.min(crop.width(), crop.height() * streamAspect);
        float height = Math.min(crop.height(), crop.width() / streamAspect);
        int x = Math.round(crop.exactCenterX() + (point.x - 0.5f) * width);
        int y = Math.round(crop.exactCenterY() + (point.y - 0.5f) * height);
        int side = Math.max(1, Math.round(Math.min(width, height) * 0.1f));
        int left = Math.max(crop.left, Math.min(crop.right - side, x - side / 2));
        int top = Math.max(crop.top, Math.min(crop.bottom - side, y - side / 2));
        return new MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private void resumeContinuousFocus(int revision) {
        if (closed || session == null || focusRevision != revision) return;
        if (pending != null) {
            handler.postDelayed(() -> resumeContinuousFocus(revision), 250);
            return;
        }
        Integer previousMode = focusMode;
        focusMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        try {
            session.setRepeatingRequest(previewRequest().build(), previewMetadata, handler);
        } catch (CameraAccessException | RuntimeException error) {
            focusMode = previousMode;
            Log.w("SpektraCam", "Could not resume continuous focus", error);
        }
    }

    void setControls(int value, boolean enabled) {
        compensation = value;
        torch = enabled;
        if (closed || session == null) return;
        try {
            session.setRepeatingRequest(previewRequest().build(), previewMetadata, handler);
        } catch (CameraAccessException | RuntimeException error) {
            Log.w("SpektraCam", "Camera request failed", error);
            fail(null);
        }
    }

    void capture(Shot callback) {
        if (closed || session == null || pending != null) {
            callback.result(null, null, context.getString(R.string.raw_not_ready));
            return;
        }
        final Pending shot = new Pending(callback);
        pending = shot;
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(reader.getSurface());
            applyControls(request);
            int[] mapModes = lens.info.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
            if (mapModes != null && Arrays.stream(mapModes).anyMatch(mode -> mode == CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
                request.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            }
            session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    if (pending != shot) return;
                    shot.result = result;
                    if (Build.VERSION.SDK_INT >= 28 && lens.physicalId != null) {
                        shot.result = result.getPhysicalCameraResults().get(lens.physicalId);
                        if (shot.result == null) {
                            complete(shot, context.getString(R.string.physical_calibration));
                            return;
                        }
                    }
                    shot.timestamp = shot.result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (shot.timestamp == null) shot.timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    match(shot);
                }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    if (pending == shot) complete(shot, context.getString(R.string.raw_capture_failed));
                }
            }, handler);
            handler.postDelayed(() -> {
                if (pending == shot) complete(shot, context.getString(R.string.raw_capture_timeout));
            }, 10_000);
        } catch (CameraAccessException | RuntimeException error) {
            Log.w("SpektraCam", "RAW capture failed", error);
            complete(shot, context.getString(R.string.raw_capture_failed));
        }
    }

    private void onImage(ImageReader source) {
        if (closed) return;
        try (Image image = source.acquireNextImage()) {
            if (image == null || pending == null) return;
            if (pending.result != null) {
                Long timestamp = pending.timestamp;
                if (timestamp != null && timestamp != image.getTimestamp()) return;
            }
            pending.frame = new Frame(image);
            match(pending);
        } catch (RuntimeException | OutOfMemoryError error) {
            if (pending != null) complete(pending, context.getString(R.string.read_raw_data));
        }
    }

    private void match(Pending shot) {
        if (shot.result == null || shot.frame == null) return;
        Long timestamp = shot.timestamp;
        if (timestamp == null) {
            complete(shot, context.getString(R.string.raw_no_timestamp));
        } else if (timestamp == shot.frame.timestamp) {
            complete(shot, null);
        } else {
            shot.frame = null;
        }
    }

    private void complete(Pending shot, String error) {
        pending = null;
        shot.callback.result(shot.frame, shot.result, error);
    }

    private void fail(String error) {
        if (closed) return;
        close();
        listener.error(error == null ? context.getString(R.string.raw_camera_failed) : error);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (session != null) session.close();
        if (device != null) device.close();
        if (reader != null) reader.close();
        if (pending != null) complete(pending, context.getString(R.string.camera_closed));
    }
}
