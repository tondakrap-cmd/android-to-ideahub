package cz.ideamirror.app;

import android.app.*;
import android.content.*;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private int selectedCameraId = -1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean finished = false;
    private SurfaceHolder previewHolder;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.prepare(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        SurfaceView preview = new SurfaceView(this);
        previewHolder = preview.getHolder();
        previewHolder.addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = Ui.text(this, "Namiřte fotoaparát na QR kód na tabuli", 18);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.rounded(this, 0xD90B1320, Ui.CARD_STROKE, 14));
        int hp = Ui.dp(this, 16);
        hint.setPadding(hp, Ui.dp(this, 10), hp, Ui.dp(this, 10));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.setMargins(hp, hp, hp, 0);
        root.addView(hint, hintLp);

        Button back = Ui.dangerButton(this, "✕ Zrušit", this::finish);
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        backLp.setMargins(hp, hp, hp, hp);
        root.addView(back, backLp);
        setContentView(root);
    }

    private void applyDisplayOrientation() {
        if (camera == null || selectedCameraId < 0) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(selectedCameraId, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            if (rotation == Surface.ROTATION_90) degrees = 90;
            else if (rotation == Surface.ROTATION_180) degrees = 180;
            else if (rotation == Surface.ROTATION_270) degrees = 270;
            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            camera.setDisplayOrientation(result);
        } catch (Exception ignored) {}
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (camera != null || finished) return;
        try {
            int count = Camera.getNumberOfCameras();
            int selected = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < count; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { selected = i; break; }
            }
            if (selected < 0 && count > 0) selected = 0;
            if (selected < 0) throw new Exception("Zařízení nemá dostupný fotoaparát. Použijte ruční vložení kódu.");
            selectedCameraId = selected;
            camera = Camera.open(selected);
            Camera.Parameters p = camera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = null;
                long score = Long.MAX_VALUE;
                for (Camera.Size s : sizes) {
                    long v = Math.abs((long) s.width * s.height - 1280L * 720);
                    if (v < score) { score = v; best = s; }
                }
                if (best != null) p.setPreviewSize(best.width, best.height);
            }
            p.setPreviewFormat(ImageFormat.NV21);
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(p);
            applyDisplayOrientation();
            camera.setPreviewDisplay(holder);
            Camera.Size actual = camera.getParameters().getPreviewSize();
            final int width = actual.width, height = actual.height;
            camera.setPreviewCallback((data, c) -> {
                if (finished || data == null || data.length < width * height || !busy.compareAndSet(false, true)) return;
                byte[] frame = data.clone();
                try {
                    worker.submit(() -> {
                        try {
                            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
                            hints.put(DecodeHintType.TRY_HARDER, true);
                            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
                            Result r = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
                            new Wire.Pair(r.getText());
                            if (!finished) {
                                finished = true;
                                runOnUiThread(() -> {
                                    setResult(RESULT_OK, new Intent().putExtra("pairing", r.getText()));
                                    finish();
                                });
                            }
                        } catch (Exception ignored) {
                        } finally {
                            busy.set(false);
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    busy.set(false);
                }
            });
            camera.startPreview();
        } catch (Exception e) {
            release();
            Ui.error(this, Ui.problem(e), this::finish);
        }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int t) {
        applyDisplayOrientation();
    }

    private void release() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }
    @Override protected void onPause() { release(); super.onPause(); }
    @Override protected void onResume() {
        super.onResume();
        if (previewHolder != null && previewHolder.getSurface().isValid()) surfaceCreated(previewHolder);
    }
    @Override protected void onDestroy() { finished = true; release(); worker.shutdownNow(); super.onDestroy(); }
}
