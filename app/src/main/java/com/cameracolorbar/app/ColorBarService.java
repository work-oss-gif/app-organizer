package com.cameracolorbar.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Runs in the background (with a notification, which Android requires).
 *
 * 1. Puts a colored strip at the very top of the screen, under the clock and battery icons.
 * 2. Keeps the back camera on at a tiny resolution.
 * 3. About 6 times a second it reads the color in the middle of the camera picture
 *    and paints the strip that color.
 */
public class ColorBarService extends Service {

    static final String ACTION_STOP = "com.cameracolorbar.app.STOP";
    private static final String CHANNEL_ID = "colorbar";
    private static final long FRAME_GAP_MS = 150;

    /** Read by the main screen to show "ON" or "off". */
    static volatile boolean running = false;

    private Handler main;
    private WindowManager wm;
    private View bar;
    private int shownColor = Color.TRANSPARENT;

    private HandlerThread camThread;
    private Handler cam;
    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean opening = false;
    private boolean screenOn = true;
    private long lastFrame = 0;

    private BroadcastReceiver screenReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        try {
            goForeground();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        main = new Handler(Looper.getMainLooper());

        try {
            addBar();
        } catch (Exception e) {
            // Usually means "Display over other apps" is not allowed.
            stopSelf();
            return START_NOT_STICKY;
        }

        camThread = new HandlerThread("camera");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        listenForScreenOnOff();
        cam.post(this::openCamera);
        return START_NOT_STICKY;
    }

    // ---------- Notification (required to run in the background) ----------

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Color bar", NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, ColorBarService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent openPi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Camera Color Bar is on")
                .setContentText("The top bar shows the back camera's color. Tap Stop to turn it off.")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Stop", stopPi).build());

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(1, b.build());
        }
    }

    // ---------- The colored strip ----------

    private void addBar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bar = new View(this);
        bar.setBackgroundColor(Color.TRANSPARENT);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                statusBarHeight(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            // Allow drawing next to the camera notch.
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Without this, newer Android pushes the strip BELOW the status bar.
            lp.setFitInsetsTypes(0);
        }
        wm.addView(bar, lp);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    /** Runs on the main thread. Blends toward the new color so it doesn't flicker. */
    private void showColor(int target) {
        if (!running || bar == null) return;
        if (Color.alpha(shownColor) == 0) {
            shownColor = target;
        } else {
            shownColor = Color.rgb(
                    (Color.red(shownColor) + Color.red(target)) / 2,
                    (Color.green(shownColor) + Color.green(target)) / 2,
                    (Color.blue(shownColor) + Color.blue(target)) / 2);
        }
        bar.setBackgroundColor(shownColor);
    }

    // ---------- Camera (runs on the "camera" thread) ----------

    private void openCamera() {
        if (!running || !screenOn || camera != null || opening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            main.post(this::stopSelf);
            return;
        }
        try {
            String id = backCameraId();
            if (id == null) {
                main.post(this::stopSelf);
                return;
            }
            Size size = smallSize(id);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, cam);
            opening = true;
            cameraManager.openCamera(id, cameraCallback, cam);
        } catch (Exception e) {
            opening = false;
            retryLater();
        }
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            opening = false;
            if (!running || !screenOn) {
                device.close();
                return;
            }
            camera = device;
            startSession();
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            // Another app (for example the Camera app) took the camera. Try again later.
            opening = false;
            device.close();
            closeCamera();
            retryLater();
        }

        @Override
        public void onError(CameraDevice device, int error) {
            opening = false;
            device.close();
            closeCamera();
            retryLater();
        }
    };

    @SuppressWarnings("deprecation")
    private void startSession() {
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (camera == null) return;
                            session = s;
                            try {
                                CaptureRequest.Builder rb =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                rb.addTarget(reader.getSurface());
                                rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                Range<Integer> fps = lowFpsRange();
                                if (fps != null) rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                                s.setRepeatingRequest(rb.build(), null, cam);
                            } catch (Exception e) {
                                closeCamera();
                                retryLater();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            closeCamera();
                            retryLater();
                        }
                    }, cam);
        } catch (Exception e) {
            closeCamera();
            retryLater();
        }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < FRAME_GAP_MS) return;
            lastFrame = now;
            final int c = centerColor(img);
            main.post(() -> showColor(c));
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /**
     * Averages a small square in the middle of the picture.
     * The camera gives "YUV" (brightness + two color channels), so we convert to normal RGB.
     */
    private static int centerColor(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yBuf = p[0].getBuffer(), uBuf = p[1].getBuffer(), vBuf = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();

        int cx = w / 2, cy = h / 2;
        int radius = Math.max(2, Math.min(w, h) / 20);
        long rs = 0, gs = 0, bs = 0;
        int n = 0;
        for (int y = cy - radius; y <= cy + radius; y += 2) {
            for (int x = cx - radius; x <= cx + radius; x += 2) {
                int Y = yBuf.get(y * yRow + x * yPix) & 0xff;
                int U = (uBuf.get((y / 2) * uRow + (x / 2) * uPix) & 0xff) - 128;
                int V = (vBuf.get((y / 2) * vRow + (x / 2) * vPix) & 0xff) - 128;
                rs += clamp(Y + 1.402f * V);
                gs += clamp(Y - 0.344f * U - 0.714f * V);
                bs += clamp(Y + 1.772f * U);
                n++;
            }
        }
        return Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n));
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    private String backCameraId() throws Exception {
        String fallback = null;
        for (String id : cameraManager.getCameraIdList()) {
            Integer facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    /** The smallest picture size the camera offers (we only need one color). */
    private Size smallSize(String id) throws Exception {
        StreamConfigurationMap map = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = new Size(320, 240);
        if (map == null) return best;
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return best;
        best = sizes[0];
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            long bestArea = (long) best.getWidth() * best.getHeight();
            if (s.getWidth() >= 160 && (area < bestArea || best.getWidth() < 160)) best = s;
        }
        return best;
    }

    /** Ask the camera to run slowly to save some battery. */
    private Range<Integer> lowFpsRange() {
        try {
            Range<Integer>[] ranges = cameraManager.getCameraCharacteristics(camera.getId())
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;
            Range<Integer> best = ranges[0];
            for (Range<Integer> r : ranges) {
                if (r.getUpper() < best.getUpper()
                        || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) {
                    best = r;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        session = null;
        camera = null;
        reader = null;
    }

    private void retryLater() {
        if (running && cam != null) cam.postDelayed(this::openCamera, 3000);
    }

    // ---------- Turn the camera off while the screen is off (saves battery) ----------

    private void listenForScreenOnOff() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                    cam.post(() -> {
                        screenOn = false;
                        cam.removeCallbacksAndMessages(null);
                        closeCamera();
                    });
                } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    cam.post(() -> {
                        screenOn = true;
                        openCamera();
                    });
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        }
        if (cam != null) {
            cam.removeCallbacksAndMessages(null);
            cam.post(this::closeCamera);
            camThread.quitSafely();
        }
        if (main != null) main.removeCallbacksAndMessages(null);
        if (bar != null && wm != null) {
            try { wm.removeView(bar); } catch (Exception ignored) { }
            bar = null;
        }
        super.onDestroy();
    }
}
