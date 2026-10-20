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
import android.widget.Toast;

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
    static final String ACTION_UPDATE = "com.cameracolorbar.app.UPDATE";
    static final String KEY_HIDE_FULLSCREEN = "hide_fullscreen"; // hide the bar during full-screen videos
    static final String ACTION_LEARN_CASE = "com.cameracolorbar.app.LEARN_CASE";
    static final String KEY_HAS_CASE = "has_case";
    static final String KEY_CASE_COLOR = "case_color";
    static final String PREFS = "settings";
    static final String KEY_OPACITY = "opacity"; // 10..100 (percent)
    static final String KEY_AUTOSTART = "autostart"; // start when the tablet turns on
    // "Silent" channel: no sound, no icon in the status bar, tucked at the bottom of the list.
    private static final String CHANNEL_ID = "colorbar_silent";
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
    // 3 more identical color strips underneath the top one (4 layers in total).
    private final View[] under = new View[3];
    private final WindowManager.LayoutParams[] underLp = new WindowManager.LayoutParams[3];
    private WindowManager.LayoutParams barLp;
    // Set by "Update now": the next reading is shown right away, even if the change is small.
    private volatile boolean forceNext = false;
    // Set by "Learn my case": the next reading is remembered as the case.
    private volatile boolean learnCaseNext = false;
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
                } else if (KEY_HIDE_FULLSCREEN.equals(key)) {
                    forceShowBar(); // re-check right away with the new setting
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
        String action = intent != null ? intent.getAction() : null;
        if (running) {
            // Already running. "Update now" = new reading right away. "Learn my case" = save the case.
            // Start pressed again = just force the bar back (manual fix).
            if (main != null) {
                if (ACTION_UPDATE.equals(action)) main.post(this::updateNow);
                else if (ACTION_LEARN_CASE.equals(action)) main.post(this::learnCase);
                else main.post(this::forceShowBar);
            }
            return START_NOT_STICKY;
        }

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
            main.postDelayed(watchdog, WATCHDOG_MS);
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
                    CHANNEL_ID, "Color bar", NotificationManager.IMPORTANCE_MIN));
        }
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, ColorBarService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent openPi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent updatePi = PendingIntent.getService(this, 2,
                new Intent(this, ColorBarService.class).setAction(ACTION_UPDATE), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Camera Color Bar is on")
                .setContentText("The camera checks the colors every 10 minutes. Tap Stop to turn it off.")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .addAction(new Notification.Action.Builder((Icon) null, "Update now", updatePi).build())
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
                // Taps always pass through (so other apps keep working normally).
                // Android 12+ then makes each such window only 80% solid (a safety rule),
                // which is why we stack several identical layers below.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

        // Several IDENTICAL color strips stacked on top of each other.
        // Each one is 80% solid, so the wallpaper behind shows through
        // 20% x 20% x 20% x 20% = 0.16%, which is invisible. Because every layer has the
        // same color, the color itself is not changed at all (no black mixing in).
        for (int i = 0; i < under.length; i++) {
            under[i] = new View(this);
            under[i].setBackgroundColor(Color.BLACK);
            underLp[i] = new WindowManager.LayoutParams();
            underLp[i].copyFrom(lp);
            wm.addView(under[i], underLp[i]);
        }
        barLp = lp;
        wm.addView(bar, lp);   // the top layer (it also listens for the status bar hiding)
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

    // ---------- Safety check: never let the bar get stuck hidden ----------

    private static final long WATCHDOG_MS = 3_000;

    /**
     * Every 3 seconds: if Android removed our strips, put them back.
     * If the strip is hidden, ask Android again whether the status bar is REALLY hidden,
     * because some apps (like Chrome search) make Android say "hidden" and then never
     * send the "it's back" message.
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            reattachIfNeeded();
            if (!barShown && bar != null) recheckStatusBar();
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void reattachIfNeeded() {
        try {
            for (int i = 0; i < under.length; i++) {
                if (under[i] != null && underLp[i] != null && !under[i].isAttachedToWindow()) {
                    wm.addView(under[i], underLp[i]);
                }
            }
            if (bar != null && barLp != null && !bar.isAttachedToWindow()) {
                wm.addView(bar, barLp);   // added after the black one, so it stays on top
            }
        } catch (Exception ignored) { }
    }

    /** Reads the CURRENT status bar state directly, instead of waiting for a message. */
    @SuppressWarnings("deprecation")
    private void recheckStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets now = bar.getRootWindowInsets();
            if (now != null && now.isVisible(WindowInsets.Type.statusBars())) {
                setBarShown(true);
            } else {
                bar.requestApplyInsets(); // ask Android to send us a fresh update
            }
        } else {
            if ((bar.getWindowSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                setBarShown(true);
            }
        }
    }

    /** Pressing Start while running: put the strips back and show them. */
    private void forceShowBar() {
        reattachIfNeeded();
        setBarShown(true);
        if (bar != null) bar.requestApplyInsets(); // if a video really is full screen, it hides again
    }

    private void setBarShown(boolean shown) {
        if (bar == null) return;
        // Setting "Hide during full-screen videos" turned off: the bar always stays.
        if (prefs != null && !prefs.getBoolean(KEY_HIDE_FULLSCREEN, true)) shown = true;
        // The 3 lower layers are simply hidden.
        int vis = shown ? View.VISIBLE : View.INVISIBLE;
        for (View v : under) if (v != null) v.setVisibility(vis);
        // The TOP layer is never made invisible, because Android stops telling invisible
        // windows when the status bar comes back (that's why the bar stayed gone after a game).
        // Instead we make it fully clear (alpha 0). It keeps listening, and taps still pass through.
        bar.setVisibility(View.VISIBLE);
        float alpha = shown ? 1f : 0f;
        if (barLp != null && barLp.alpha != alpha) {
            barLp.alpha = alpha;
            try { wm.updateViewLayout(bar, barLp); } catch (Exception ignored) { }
        }
        if (shown == barShown) return;
        barShown = shown;
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
            // Every layer shows the same picture, so all 4 layers are exactly the same color.
            bar.setBackground(pictureDrawable());
            for (View v : under) if (v != null) v.setBackground(pictureDrawable());
            hasColor = false;
        }
        for (int i = 0; i < s.pixels.length; i++) {
            int c = s.pixels[i];
            target[i * 3] = Color.red(c);
            target[i * 3 + 1] = Color.green(c);
            target[i * 3 + 2] = Color.blue(c);
        }
        if (!hasColor || forceNext) {
            // Very first colors, or the user pressed "Update now":
            // show the new color immediately, even if it's only a small change.
            forceNext = false;
            fading = false;
            main.removeCallbacks(fadeStep);
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
        for (View v : under) if (v != null) v.invalidate();
    }

    private BitmapDrawable pictureDrawable() {
        BitmapDrawable d = new BitmapDrawable(getResources(), picture);
        d.setFilterBitmap(true);  // smooth stretching instead of blocky pixels
        return d;
    }

    /** "Update now": show the bar, look through the camera right away, show the color at once. */
    private void updateNow() {
        forceShowBar();
        forceNext = true;
        readingNow();
    }

    /** "Learn my case": the next reading is saved as "this is what my case looks like". */
    private void learnCase() {
        forceShowBar();
        learnCaseNext = true;
        readingNow();
    }

    private void readingNow() {
        if (cam == null) return;
        cam.post(() -> {
            if (camera != null || opening) return; // a reading is already on its way
            cam.removeCallbacks(openRunnable);
            openCamera();
        });
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
            if (s != null) main.post(() -> handleReading(s));
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
        final int raw;          // plain average color (no boost)
        final float brightness; // 0 = black .. 255 = white
        final float detail;     // how much the brightness varies across the picture
        final float sharpness;  // how different neighbouring points are
        Slice(int w, int h, int[] pixels, int raw, float brightness, float detail, float sharpness) {
            this.w = w; this.h = h; this.pixels = pixels;
            this.raw = raw; this.brightness = brightness; this.detail = detail; this.sharpness = sharpness;
        }
    }

    /**
     * Decides whether the camera could see the room normally.
     * Returns null if the picture is fine, otherwise a short reason why it's skipped.
     */
    private String whyNotClear(Slice s) {
        if (s.brightness < 28) {
            return "the camera sees almost nothing (too dark, covered or in a bag)";
        }
        if (s.detail < 6 && s.sharpness < 3) {
            return "the camera looks blocked (no detail at all)";
        }
        if (prefs != null && prefs.getBoolean(KEY_HAS_CASE, false)) {
            int cc = prefs.getInt(KEY_CASE_COLOR, 0);
            float dr = Color.red(s.raw) - Color.red(cc);
            float dg = Color.green(s.raw) - Color.green(cc);
            float db = Color.blue(s.raw) - Color.blue(cc);
            float dist = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            if (dist < 40 && s.detail < 20) {
                return "the camera is covered by your case";
            }
        }
        return null;
    }

    /** Main thread: a new reading arrived. Learn the case, skip it, or show it. */
    private void handleReading(Slice s) {
        if (learnCaseNext) {
            learnCaseNext = false;
            prefs.edit().putBoolean(KEY_HAS_CASE, true).putInt(KEY_CASE_COLOR, s.raw).apply();
            toast("Case saved. When the camera sees your case, the color won't change.");
            return;
        }
        String why = whyNotClear(s);
        if (why != null) {
            // Keep the current color, silently (no message).
            forceNext = false;
            return;
        }
        showPicture(s);
    }

    private void toast(String text) {
        try { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
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
            long rawR = 0, rawG = 0, rawB = 0;     // plain average, used to recognise the case
            int[] lum = new int[grid * grid];      // brightness of every sample point
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
                    rawR += pr; rawG += pg; rawB += pb;
                    lum[a * grid + c] = Y;
                }
            }
            px[z] = boost(Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n)));

            // ---- Can the camera see normally? ----
            int count = grid * grid;
            double mean = 0;
            for (int L : lum) mean += L;
            mean /= count;
            double var = 0;
            for (int L : lum) var += (L - mean) * (L - mean);
            double std = Math.sqrt(var / count);          // how much brightness varies (detail)
            double edge = 0; int edges = 0;               // how sharp neighbouring points differ
            for (int a = 0; a < grid; a++) {
                for (int c = 0; c < grid; c++) {
                    if (a + 1 < grid) { edge += Math.abs(lum[a * grid + c] - lum[(a + 1) * grid + c]); edges++; }
                    if (c + 1 < grid) { edge += Math.abs(lum[a * grid + c] - lum[a * grid + c + 1]); edges++; }
                }
            }
            edge /= Math.max(1, edges);
            int raw = Color.rgb((int) (rawR / count), (int) (rawG / count), (int) (rawB / count));
            return new Slice(zones, 1, px, raw, (float) mean, (float) std, (float) edge);
        }
        return null;
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
        for (int i = 0; i < under.length; i++) {
            if (under[i] != null && wm != null) {
                try { wm.removeView(under[i]); } catch (Exception ignored) { }
                under[i] = null;
            }
        }
        super.onDestroy();
    }
}
