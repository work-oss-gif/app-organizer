package com.roomcolorwallpaper.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
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
import android.os.PowerManager;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The background helper. Every 10 minutes it:
 *  1. checks the screen is on (no point changing the wallpaper when nobody sees it),
 *  2. turns on the back camera for about 1.5 seconds so brightness can adjust,
 *  3. reads the room's 3 main colors and turns the camera off again,
 *  4. sets a new wallpaper on the screen you chose, unless the room is dark
 *     or the colors are almost the same as last time.
 *
 * Android only lets an app use the camera in the background if it shows a notification
 * ("foreground service"). Like Camera Color Bar, the app does not ask for notification
 * permission, so on Android 13+ that notification stays hidden.
 */
public class AutoService extends Service {

    static final String EXTRA_NOW = "now";               // do an update right away
    private static final long EVERY_MS = 10 * 60 * 1000L; // 10 minutes
    private static final long SETTLE_MS = 1500;           // let brightness adjust
    private static final long GIVE_UP_MS = 8000;          // stop trying if the camera is stuck
    private static final String CHANNEL_ID = "auto";
    private static final int GRID_W = 64, GRID_H = 48;    // how many dots to read

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private long startedAt;
    private boolean busy;   // a reading is in progress
    private boolean done;   // this reading already got its picture

    // ---------------------------------------------------------------- start / stop

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("auto-wallpaper");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = ColorMaker.prefs(this);
        if (!p.getBoolean(ColorMaker.KEY_AUTO, false)
                || p.getInt(ColorMaker.KEY_TARGET, 0) == 0
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            showNotification();
        } catch (Exception e) {
            // Android refused to let us run in the background right now (for example
            // when restarted without the app being opened). Open the app to start it again.
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean now = intent != null && intent.getBooleanExtra(EXTRA_NOW, false);
        handler.removeCallbacks(tick);
        if (now) handler.post(tick); else handler.postDelayed(tick, EVERY_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() { @Override public void run() { closeCamera(); } });
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Auto wallpaper", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Room Color Wallpaper")
                .setContentText("Updating your wallpaper every 10 minutes")
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(1, b.build());
        }
    }

    // ---------------------------------------------------------------- every 10 minutes

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            handler.postDelayed(this, EVERY_MS); // book the next one first
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (busy || !pm.isInteractive()) return; // already working, or screen is off
            openCamera();
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override public void run() { closeCamera(); }
    };

    // ---------------------------------------------------------------- camera

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = pickBackCamera(manager);
            if (id == null) return;
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = smallSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
            }, handler);
            busy = true;
            done = false;
            handler.postDelayed(giveUp, GIVE_UP_MS);
            manager.openCamera(id, cameraCallback, handler);
        } catch (Exception e) {
            closeCamera(); // camera busy (for example the app is open); try again next time
        }
    }

    private static String pickBackCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    /** Smallest picture size that is at least 320 wide: enough to read colors, light on battery. */
    private static Size smallSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() >= 320 && (best == null || s.getWidth() < best.getWidth())) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            camera = device;
            try {
                device.createCaptureSession(Arrays.asList(reader.getSurface()), sessionCallback, handler);
            } catch (Exception e) {
                closeCamera();
            }
        }
        @Override public void onDisconnected(CameraDevice device) { device.close(); camera = null; closeCamera(); }
        @Override public void onError(CameraDevice device, int error) { device.close(); camera = null; closeCamera(); }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession s) {
            if (camera == null) return;
            session = s;
            try {
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(reader.getSurface());
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                startedAt = System.currentTimeMillis();
                s.setRepeatingRequest(b.build(), null, handler);
            } catch (Exception e) {
                closeCamera();
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) { closeCamera(); }
    };

    /** Called for every camera picture. Ignores them until brightness has settled, then uses one. */
    private void onFrame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        int[] px;
        try {
            if (done || System.currentTimeMillis() - startedAt < SETTLE_MS) return;
            done = true;
            px = readColors(img);
        } finally {
            img.close();
        }
        // Turn the camera off first, then do the slower wallpaper work.
        handler.post(new Runnable() { @Override public void run() { closeCamera(); } });
        final int[] dots = px;
        handler.post(new Runnable() { @Override public void run() { update(dots); } });
    }

    /**
     * The camera gives "YUV" pictures (brightness + two color parts) instead of red/green/blue.
     * This reads a 64 x 48 grid of dots and converts each one to a normal color.
     */
    private static int[] readColors(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer(), uBuf = planes[1].getBuffer(), vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        int[] out = new int[GRID_W * GRID_H];
        int i = 0;
        for (int gy = 0; gy < GRID_H; gy++) {
            int y = gy * h / GRID_H;
            for (int gx = 0; gx < GRID_W; gx++) {
                int x = gx * w / GRID_W;
                int Y = yBuf.get(y * yRow + x * yPix) & 0xFF;
                int U = (uBuf.get((y / 2) * uRow + (x / 2) * uPix) & 0xFF) - 128;
                int V = (vBuf.get((y / 2) * vRow + (x / 2) * vPix) & 0xFF) - 128;
                int r = clamp(Math.round(Y + 1.402f * V));
                int g = clamp(Math.round(Y - 0.344f * U - 0.714f * V));
                int b = clamp(Math.round(Y + 1.772f * U));
                out[i++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private void closeCamera() {
        handler.removeCallbacks(giveUp);
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (camera != null) { try { camera.close(); } catch (Exception ignored) { } camera = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        busy = false;
    }

    // ---------------------------------------------------------------- wallpaper

    private void update(int[] dots) {
        int[] colors = ColorMaker.mainColors(dots);
        if (colors == null) return;                          // too dark: keep the current wallpaper
        if (ColorMaker.sameAsLast(this, colors)) return;     // room hasn't changed: nothing to do
        SharedPreferences p = ColorMaker.prefs(this);
        int which = p.getInt(ColorMaker.KEY_TARGET, 0);
        if (which == 0) return;
        try {
            ColorMaker.apply(this, colors, p.getBoolean(ColorMaker.KEY_SHAPES, false), which);
        } catch (Exception ignored) {
            // Could not set it this time; try again in 10 minutes.
        }
    }
}
