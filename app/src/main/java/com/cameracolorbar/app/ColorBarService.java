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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
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
 * 2. Every 10 minutes it turns the back camera on for about 2 seconds, takes one reading
 *    (one averaged color for the whole strip), and turns the camera off again.
 * 3. Only if the color changed a LOT, the strip fades to it over 3 seconds.
 */
public class ColorBarService extends Service {

    static final String ACTION_STOP = "com.cameracolorbar.app.STOP";
    static final String PREFS = "settings";
    static final String KEY_OPACITY = "opacity"; // 10..100 (percent)
    static final String KEY_AUTOSTART = "autostart"; // start when the tablet turns on
    private static final String CHANNEL_ID = "colorbar";
    // Timing
    private static final long CHECK_EVERY_MS = 600_000; // look through the camera at most once every 10 minutes
    private static final long SETTLE_MS = 1_500;       // give the camera 1.5 s to adjust brightness first
    private static final long FADE_MS = 3_000;         // a color change takes 3 seconds
    // How different a new color must be before the strip changes (0..440).
    // 70 = for example a beige wall to a green plant, but NOT a shadow or a light being dimmed a bit.
    private static final float MAJOR_CHANGE = 70f;

    /** Read by the main screen to show "ON" or "off". */
    static volatile boolean running = false;

    private Handler main;
    private WindowManager wm;
    private View bar;
    private View blackBar;   // plain black strip underneath the color strip
    private WindowManager.LayoutParams barLp, blackLp;
    // Fading: over 3 seconds the strip goes from the old colors (fadeFrom) to the new ones (target).
    private static final long FADE_FRAME_MS = 16;   // about 60 small steps per second
    private long fadeStart = 0;
    // The strip is a tiny picture with one color per inch, smoothly stretched to fill the strip.
    private boolean hasColor = false;
    private int picW = 0, picH = 0;          // size of the strip picture
    private float[] shown = new float[0];    // r,g,b per zone: what's on screen now
    private float[] target = new float[0];   // r,g,b per zone: what the camera saw last
    private float[] fadeFrom = new float[0]; // r,g,b per zone: where the current fade started
    private int[] out = new int[0];
    private Bitmap picture;
    private boolean fading = false;
    private volatile int sensorOrientation = 90;

    // Strength from the slider: 1.0 = full camera color, lower = fades into black, 0 = pure black.
    // The strip itself is always solid (the wallpaper never shows).
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
    private long sessionStart = 0;   // when the camera started sending pictures this time
    private long lastReading = 0;    // when we last took a reading (0 = never)
    private boolean gotReading = false;
    private final Runnable openRunnable = this::openCamera;

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
        cam.post(this::scheduleNext); // first reading right away
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
                .setContentText("The camera checks the colors every 10 minutes. Tap Stop to turn it off.")
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
        bar.setBackgroundColor(Color.BLACK);  // black (never see-through) until the first camera color

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                statusBarHeight(),
                type,
                // IMPORTANT: no FLAG_NOT_TOUCHABLE while the strip is showing.
                // Android 12+ automatically makes "draw over other apps" windows that let
                // touches pass through them 20% see-through (a safety rule). Without that flag
                // the strip stays 100% solid. The real status bar sits above our strip,
                // so it still gets all the touches (pulling down notifications works).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);   // the window itself can't be see-through
        lp.alpha = 1f;
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

        // Two strips stacked: a plain BLACK strip on the bottom layer, the color strip on top.
        // Android stacks windows in the order they are added, so the black one goes in first.
        // If the color strip is ever a little see-through, you see black through it, not the wallpaper.
        blackBar = new View(this);
        blackBar.setBackgroundColor(Color.BLACK);
        blackLp = new WindowManager.LayoutParams();
        blackLp.copyFrom(lp);
        wm.addView(blackBar, blackLp);

        barLp = lp;
        wm.addView(bar, lp);
    }

    /**
     * While the strip is hidden (full-screen video/game) it must not catch any taps,
     * so we turn "let touches pass through" back on. While it's showing, it stays off
     * so Android keeps the strip fully solid.
     */
    private void setPassTouches(boolean pass) {
        int f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            if (barLp != null && bar != null) {
                barLp.flags = pass ? (barLp.flags | f) : (barLp.flags & ~f);
                wm.updateViewLayout(bar, barLp);
            }
            if (blackLp != null && blackBar != null) {
                blackLp.flags = pass ? (blackLp.flags | f) : (blackLp.flags & ~f);
                wm.updateViewLayout(blackBar, blackLp);
            }
        } catch (Exception ignored) { }
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
        if (blackBar != null) blackBar.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        if (shown == barShown) return;
        barShown = shown;
        setPassTouches(!shown);
        if (cam == null) return;
        // Strip hidden -> turn the camera off. Strip back -> turn the camera on again.
        cam.post(() -> {
            if (barShown) {
                scheduleNext(); // still at most once every 10 minutes
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
            fadeFrom = new float[picW * picH * 3];
            out = new int[picW * picH];
            picture = Bitmap.createBitmap(picW, picH, Bitmap.Config.ARGB_8888);
            BitmapDrawable d = new BitmapDrawable(getResources(), picture);
            d.setFilterBitmap(true);  // smooth stretching instead of blocky pixels
            // Solid black underneath the color, so nothing behind can ever show through.
            bar.setBackground(new LayerDrawable(new Drawable[]{new ColorDrawable(Color.BLACK), d}));
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
            return;
        }
        // Only a MAJOR color change counts. Measure how far the new color is from the
        // one on screen (0 = identical, about 440 = black vs white). Below the limit: ignore it,
        // and keep the current color exactly as it is.
        float dr = target[0] - shown[0], dg = target[1] - shown[1], db = target[2] - shown[2];
        float distance = (float) Math.sqrt(dr * dr + dg * dg + db * db);
        if (distance < MAJOR_CHANGE) {
            System.arraycopy(shown, 0, target, 0, shown.length); // forget the small change
            return;
        }

        // Start a new 3-second fade from whatever is on screen right now.
        System.arraycopy(shown, 0, fadeFrom, 0, shown.length);
        fadeStart = SystemClock.uptimeMillis();
        if (!fading) {
            fading = true;
            main.post(fadeStep);
        }
    }

    /**
     * One small step of the fade, about 60 times a second, for 3 seconds.
     * It starts gently, speeds up in the middle and slows down at the end,
     * so you can't see where the change begins or stops.
     */
    private final Runnable fadeStep = new Runnable() {
        @Override
        public void run() {
            if (!running || bar == null) {
                fading = false;
                return;
            }
            float t = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
            float e = t * t * (3f - 2f * t); // gentle start and end
            for (int k = 0; k < shown.length; k++) {
                shown[k] = fadeFrom[k] + (target[k] - fadeFrom[k]) * e;
            }
            paint();

            if (t >= 1f) {
                fading = false; // done; nothing runs until the next reading
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
        // The slider fades the color into the black layer underneath:
        // 100% = full color, 50% = half color + half black (darker), 0% = pure black.
        // We work out the mixed color here and paint it solid, so the wallpaper can never show.
        for (int i = 0; i < out.length; i++) {
            out[i] = Color.rgb(
                    clamp(shown[i * 3] * strength),
                    clamp(shown[i * 3 + 1] * strength),
                    clamp(shown[i * 3 + 2] * strength));
        }
        picture.setPixels(out, 0, picW, 0, 0, picW, picH);
        bar.invalidate();
    }

    private void readOpacity() {
        int pct = prefs.getInt(KEY_OPACITY, 100);
        pct = Math.max(0, Math.min(100, pct));
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
                            sessionStart = SystemClock.uptimeMillis();
                            gotReading = false;
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

    /**
     * The camera is only on for about 2 seconds every 10 minutes:
     * wait 1.5 s so it can adjust to the room's light, take ONE reading,
     * turn the camera off, and come back in 10 minutes.
     */
    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || gotReading) return;
            if (SystemClock.uptimeMillis() - sessionStart < SETTLE_MS) return; // still adjusting
            gotReading = true;
            final Slice s = slice(img, imageRotation());
            if (s != null) main.post(() -> showPicture(s));
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
        if (gotReading) {
            lastReading = SystemClock.uptimeMillis();
            cam.post(() -> {
                closeCamera();   // camera OFF until the next check
                scheduleNext();
            });
        }
    }

    /** Plan the next camera check so there is at most one every 10 minutes. */
    private void scheduleNext() {
        if (!running || cam == null) return;
        cam.removeCallbacks(openRunnable);
        long wait = lastReading == 0 ? 0
                : Math.max(0, lastReading + CHECK_EVERY_MS - SystemClock.uptimeMillis());
        cam.postDelayed(openRunnable, wait);
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

        // ONE color for the whole strip: the average of the whole middle band of the camera view.
        int zones = 1;

        // Each zone averages a tall area of the camera view (the middle 40%),
        // so the color reflects the surroundings, not one tiny spot.
        final float top = 0.30f, band = 0.40f;
        final int grid = 24; // 24 x 24 sample points spread over the area

        Image.Plane[] p = img.getPlanes();
        ByteBuffer yBuf = p[0].getBuffer(), uBuf = p[1].getBuffer(), vBuf = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();

        int[] px = new int[zones];
        for (int z = 0; z < zones; z++) {
            // Weighted average: colorful pixels count much more than gray/white/black ones,
            // so the result is the room's main COLOR instead of a muddy gray mix.
            double rs = 0, gs = 0, bs = 0, n = 0;
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
                    int pr = clamp(Y + 1.402f * V);
                    int pg = clamp(Y - 0.344f * U - 0.714f * V);
                    int pb = clamp(Y + 1.772f * U);
                    // How colorful is this pixel? 0 = gray, 1 = pure color.
                    float colorful = (Math.max(pr, Math.max(pg, pb)) - Math.min(pr, Math.min(pg, pb))) / 255f;
                    float weight = 0.1f + colorful * colorful * 10f;
                    rs += pr * weight;
                    gs += pg * weight;
                    bs += pb * weight;
                    n += weight;
                }
            }
            px[z] = boost(Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n)));
        }
        // A picture "zones" pixels wide and 1 tall. When Android stretches it smoothly
        // across the strip, neighbouring inches blend into each other like a gradient.
        return new Slice(zones, 1, px);
    }

    /**
     * Makes a color about twice as vivid and a little brighter,
     * so a slightly green room gives a clear green, not gray-olive.
     * Grays stay gray (there is no color to boost).
     */
    private static int boost(int c) {
        float[] hsv = new float[3];
        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 2.0f);               // colorfulness x2
        hsv[2] = Math.min(1f, 0.15f + hsv[2] * 1.1f);        // a bit brighter, never pitch black
        return Color.HSVToColor(hsv);
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
        if (running && cam != null) {
            cam.removeCallbacks(openRunnable);
            cam.postDelayed(openRunnable, 3000);
        }
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
                        scheduleNext(); // still at most once every 10 minutes
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
        if (blackBar != null && wm != null) {
            try { wm.removeView(blackBar); } catch (Exception ignored) { }
            blackBar = null;
        }
        super.onDestroy();
    }
}
