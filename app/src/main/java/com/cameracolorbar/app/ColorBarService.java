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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
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
import android.view.WindowInsets;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Runs in the background (with a notification, which Android requires).
 *
 * 1. Puts a colored strip at the very top of the screen, under the clock and battery icons.
 * 2. Keeps the back camera on at a tiny resolution.
 * 3. About 6 times a second it splits the camera view into one zone per inch of strip,
 *    averages each zone, and the strip fades smoothly toward those colors.
 */
public class ColorBarService extends Service {

    static final String ACTION_STOP = "com.cameracolorbar.app.STOP";
    static final String PREFS = "settings";
    static final String KEY_OPACITY = "opacity"; // 10..100 (percent)
    static final String KEY_AUTOSTART = "autostart"; // start when the tablet turns on
    private static final String CHANNEL_ID = "colorbar";
    private static final long FRAME_GAP_MS = 150;

    /** Read by the main screen to show "ON" or "off". */
    static volatile boolean running = false;

    private Handler main;
    private WindowManager wm;
    private View bar;
    // Fading: the color on screen (shown) slowly walks toward the camera's color (target).
    // Stored as decimals so tiny steps are not lost to rounding.
    private static final long FADE_FRAME_MS = 16;   // about 60 small steps per second
    private static final float FADE_STEP = 0.04f;   // move 4% of the remaining distance each step
    // The strip is a small picture: every pixel gets its own color from the camera.
    // It is a thin horizontal slice through the middle of what the camera sees,
    // one strip pixel per camera pixel, then smoothly stretched to fill the strip.
    private boolean hasColor = false;
    private int picW = 0, picH = 0;        // size of the strip picture in camera pixels
    private float[] shown = new float[0];  // r,g,b for every pixel, what's on screen now
    private float[] target = new float[0]; // r,g,b for every pixel, what the camera sees
    private int[] out = new int[0];
    private Bitmap picture;
    private boolean fading = false;
    private volatile int sensorOrientation = 90;

    // Strength from the slider: how colorful the strip is. 0.1 = almost gray, 1.0 = full color.
    // The strip itself is always solid (never see-through).
    private float strength = 1f;
    private SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                if (KEY_OPACITY.equals(key)) {
                    readOpacity();
                    if (hasColor && bar != null) paint();
                }
            };

    private HandlerThread camThread;
    private Handler cam;
    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean opening = false;
    private boolean screenOn = true;
    // False while Android hides the status bar (full-screen video, game). Camera stays off then.
    private volatile boolean barShown = true;
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

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        readOpacity();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

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
        bar.setBackgroundColor(Color.TRANSPARENT);  // until the first camera picture arrives

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
        followStatusBar();
        wm.addView(bar, lp);
    }

    /**
     * Only show the strip when Android is showing the status bar.
     * When an app goes full screen (video, game), Android hides the status bar
     * and tells every window about it. We listen for that and hide/show our strip.
     */
    @SuppressWarnings("deprecation")
    private void followStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11 and newer
            bar.setOnApplyWindowInsetsListener((v, insets) -> {
                setBarShown(insets.isVisible(WindowInsets.Type.statusBars()));
                return insets;
            });
        } else {
            // Android 10 and older
            bar.setOnSystemUiVisibilityChangeListener(vis ->
                    setBarShown((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0));
        }
    }

    private void setBarShown(boolean shown) {
        if (bar == null) return;
        bar.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        if (shown == barShown) return;
        barShown = shown;
        if (cam == null) return;
        // Strip hidden -> turn the camera off. Strip back -> turn the camera on again.
        cam.post(() -> {
            if (barShown) {
                openCamera();
            } else {
                cam.removeCallbacksAndMessages(null);
                closeCamera();
            }
        });
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    /**
     * Runs on the main thread when the camera reports a new color.
     * It does NOT paint the color right away. It only sets the goal,
     * and the fade loop below walks there gradually.
     */
    private void showPicture(Slice s) {
        if (!running || bar == null) return;
        if (s.w != picW || s.h != picH) {
            // First picture, or the tablet was turned: set up new arrays and snap to it.
            picW = s.w;
            picH = s.h;
            shown = new float[picW * picH * 3];
            target = new float[picW * picH * 3];
            out = new int[picW * picH];
            picture = Bitmap.createBitmap(picW, picH, Bitmap.Config.ARGB_8888);
            BitmapDrawable d = new BitmapDrawable(getResources(), picture);
            d.setFilterBitmap(true);  // smooth stretching instead of blocky pixels
            bar.setBackground(d);
            hasColor = false;
        }
        for (int i = 0; i < s.pixels.length; i++) {
            int c = s.pixels[i];
            target[i * 3] = Color.red(c);
            target[i * 3 + 1] = Color.green(c);
            target[i * 3 + 2] = Color.blue(c);
        }
        if (!hasColor) {
            // Very first colors: start from them so the bar doesn't fade in from nothing.
            System.arraycopy(target, 0, shown, 0, shown.length);
            hasColor = true;
            paint();
        }
        if (!fading) {
            fading = true;
            main.post(fadeStep);
        }
    }

    /**
     * One small step of the fade, about 60 times a second.
     * Each step moves 4% of the way that is left, so a big change
     * (for example red to blue) blends over roughly one second, and small
     * changes are barely noticeable.
     */
    private final Runnable fadeStep = new Runnable() {
        @Override
        public void run() {
            if (!running || bar == null) {
                fading = false;
                return;
            }
            boolean arrived = true;
            for (int k = 0; k < shown.length; k++) {
                shown[k] += (target[k] - shown[k]) * FADE_STEP;
                if (Math.abs(target[k] - shown[k]) >= 0.5f) arrived = false;
            }
            paint();

            if (arrived) {
                fading = false; // stop the loop to save battery until a new color comes in
            } else {
                main.postDelayed(this, FADE_FRAME_MS);
            }
        }
    };

    /**
     * Paints the strip. Always fully solid.
     * The slider mixes the camera color with a gray of the same brightness:
     * full strength = the real color, low strength = a soft, grayish version of it.
     */
    private void paint() {
        if (picture == null) return;
        for (int i = 0; i < out.length; i++) {
            float r0 = shown[i * 3], g0 = shown[i * 3 + 1], b0 = shown[i * 3 + 2];
            float gray = 0.299f * r0 + 0.587f * g0 + 0.114f * b0;
            out[i] = Color.rgb(
                    clamp(gray + (r0 - gray) * strength),
                    clamp(gray + (g0 - gray) * strength),
                    clamp(gray + (b0 - gray) * strength));
        }
        picture.setPixels(out, 0, picW, 0, 0, picW, picH);
        bar.invalidate();
    }

    private void readOpacity() {
        int pct = prefs.getInt(KEY_OPACITY, 100);
        pct = Math.max(10, Math.min(100, pct));
        strength = pct / 100f;
    }

    // ---------- Camera (runs on the "camera" thread) ----------

    private void openCamera() {
        if (!running || !screenOn || !barShown || camera != null || opening) return;
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
            Integer so = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so != null ? so : 90;
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
            if (!running || !screenOn || !barShown) {
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
            final Slice s = slice(img, imageRotation());
            if (s != null) main.post(() -> showPicture(s));
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /** A small picture (w x h pixels) cut from the camera image. */
    private static final class Slice {
        final int w, h;
        final int[] pixels;
        Slice(int w, int h, int[] pixels) { this.w = w; this.h = h; this.pixels = pixels; }
    }

    /**
     * How many degrees the camera picture must be turned to look upright on the screen.
     * Depends on how the camera is mounted and how the tablet is being held.
     */
    @SuppressWarnings("deprecation")
    private int imageRotation() {
        int deg = wm.getDefaultDisplay().getRotation() * 90; // ROTATION_0..270 are 0..3
        return (sensorOrientation - deg + 360) % 360;
    }

    /**
     * Cuts a thin horizontal band through the middle of the camera picture,
     * with the same shape as the status bar, keeping every camera pixel.
     * The camera gives "YUV" (brightness + two color channels), so each pixel is converted to RGB.
     */
    @SuppressWarnings("deprecation")
    private Slice slice(Image img, int rot) {
        int w = img.getWidth(), h = img.getHeight();
        boolean sideways = rot == 90 || rot == 270;
        int uprightW = sideways ? h : w;
        int uprightH = sideways ? w : h;

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int stripW = dm.widthPixels, stripH = statusBarHeight();
        if (stripW <= 0 || stripH <= 0) return null;

        // One color per inch of strip. xdpi = how many screen pixels make one inch.
        float inches = stripW / Math.max(1f, dm.xdpi);
        int zones = Math.max(2, Math.round(inches));

        // Each zone averages a tall area of the camera view (the middle 40%),
        // so the color reflects the surroundings, not one tiny spot.
        final float top = 0.30f, band = 0.40f;
        final int grid = 12; // 12 x 12 sample points per zone

        Image.Plane[] p = img.getPlanes();
        ByteBuffer yBuf = p[0].getBuffer(), uBuf = p[1].getBuffer(), vBuf = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();

        int[] px = new int[zones];
        for (int z = 0; z < zones; z++) {
            long rs = 0, gs = 0, bs = 0;
            int n = 0;
            for (int a = 0; a < grid; a++) {
                float u = (z + (a + 0.5f) / grid) / zones;       // 0 = left, 1 = right
                for (int c = 0; c < grid; c++) {
                    float v = top + (c + 0.5f) / grid * band;     // 0 = top, 1 = bottom
                    // Turn the screen position into a position in the (possibly sideways) camera image.
                    float xn, yn;
                    switch (rot) {
                        case 90:  xn = v;      yn = 1f - u; break;
                        case 180: xn = 1f - u; yn = 1f - v; break;
                        case 270: xn = 1f - v; yn = u;      break;
                        default:  xn = u;      yn = v;      break;
                    }
                    int x = Math.min(w - 1, (int) (xn * w));
                    int y = Math.min(h - 1, (int) (yn * h));
                    int Y = yBuf.get(y * yRow + x * yPix) & 0xff;
                    int U = (uBuf.get((y / 2) * uRow + (x / 2) * uPix) & 0xff) - 128;
                    int V = (vBuf.get((y / 2) * vRow + (x / 2) * vPix) & 0xff) - 128;
                    rs += clamp(Y + 1.402f * V);
                    gs += clamp(Y - 0.344f * U - 0.714f * V);
                    bs += clamp(Y + 1.772f * U);
                    n++;
                }
            }
            px[z] = Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n));
        }
        // A picture "zones" pixels wide and 1 tall. When Android stretches it smoothly
        // across the strip, neighbouring inches blend into each other like a gradient.
        return new Slice(zones, 1, px);
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
            // Small picture is enough for averaged colors (saves battery).
            if (s.getWidth() >= 320 && (area < bestArea || best.getWidth() < 320)) best = s;
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
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
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
