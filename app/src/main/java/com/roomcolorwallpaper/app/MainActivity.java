package com.roomcolorwallpaper.app;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

/**
 * Room Color Wallpaper: looks at your room through the back camera,
 * finds its 3 main colors, and turns them into a wallpaper.
 *
 * How it works, step by step:
 *  1. The app opens and shows what the back camera sees (asks for camera permission once).
 *  2. After about 1.5 seconds (so brightness can adjust) it reads the colors automatically.
 *     Tap "Capture colors" any time to read them again.
 *  3. It finds the 3 most common, clearly different colors and draws a wallpaper from them.
 *  4. Tap "Home", "Lock" or "Both" to set it as your wallpaper.
 *  5. Tap "Auto every 10 min" to keep updating that same screen by itself (see AutoService).
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final long SETTLE_MS = 1500; // time to let brightness adjust
    static final String EXTRA_AUTOSTART = "autostart"; // sent after the tablet turns on

    // Screen parts
    private TextureView preview;
    private ImageView wallpaperPreview;
    private TextView status;
    private LinearLayout swatchRow;
    private Button captureBtn, styleBtn, homeBtn, lockBtn, bothBtn, autoBtn, rebootBtn;
    private float density;

    // Camera parts
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private boolean wantCamera;
    private boolean askedPermission;

    // Result
    private int[] colors;          // the 3 room colors, most common first
    private boolean shapesStyle;   // false = smooth gradient, true = soft shapes

    // ---------------------------------------------------------------- screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Opened by BootReceiver after the tablet turned on: restart auto updates and close.
        if (getIntent().getBooleanExtra(EXTRA_AUTOSTART, false)) {
            if (ColorMaker.prefs(this).getBoolean(ColorMaker.KEY_AUTO, false)) {
                Intent i = new Intent(this, AutoService.class).putExtra(AutoService.EXTRA_NOW, true);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                } catch (Exception ignored) { }
            }
            finish();
            return;
        }

        shapesStyle = ColorMaker.prefs(this).getBoolean(ColorMaker.KEY_SHAPES, false);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // Top: live camera view.
        preview = new TextureView(this);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Bottom panel.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));
        panel.setBackgroundColor(0xFF1A1A1A);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setText("Point the back camera at your room.");
        panel.addView(status);

        // Row: 3 color circles + small wallpaper preview.
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(10);
        panel.addView(middle, mp);

        swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        middle.addView(swatchRow);

        wallpaperPreview = new ImageView(this);
        wallpaperPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(70), dp(110));
        wp.leftMargin = dp(20);
        middle.addView(wallpaperPreview, wp);

        // Buttons.
        LinearLayout row1 = buttonRow(panel);
        captureBtn = addButton(row1, "Capture colors");
        styleBtn = addButton(row1, shapesStyle ? "Style: Shapes" : "Style: Smooth");
        LinearLayout row2 = buttonRow(panel);
        homeBtn = addButton(row2, "Set Home");
        lockBtn = addButton(row2, "Set Lock");
        bothBtn = addButton(row2, "Set Both");
        LinearLayout row3 = buttonRow(panel);
        autoBtn = addButton(row3, "Auto every 10 min: OFF");
        rebootBtn = addButton(row3, "Allow restart after reboot");

        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCapture(); }
        });
        styleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                shapesStyle = !shapesStyle;
                styleBtn.setText(shapesStyle ? "Style: Shapes" : "Style: Smooth");
                ColorMaker.prefs(MainActivity.this).edit().putBoolean(ColorMaker.KEY_SHAPES, shapesStyle).apply();
                showResult();
            }
        });
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWallpaper(WallpaperManager.FLAG_SYSTEM); }
        });
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWallpaper(WallpaperManager.FLAG_LOCK); }
        });
        bothBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setWallpaper(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            }
        });
        autoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAutoButton(); }
        });
        rebootBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlaySettings(); }
        });
        setWallpaperButtonsEnabled(false);
        updateAutoButtons();

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCameraIfWanted(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    private LinearLayout buttonRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(row, lp);
        return row;
    }

    private Button addButton(LinearLayout row, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        row.addView(b, lp);
        return b;
    }

    private void setWallpaperButtonsEnabled(boolean on) {
        homeBtn.setEnabled(on);
        lockBtn.setEnabled(on);
        bothBtn.setEnabled(on);
        styleBtn.setEnabled(on);
    }

    private int dp(int v) { return (int) (v * density); }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutoButtons();
        // Opening the app also restarts auto updates if Android stopped them.
        if (ColorMaker.prefs(this).getBoolean(ColorMaker.KEY_AUTO, false) && hasCameraPermission()) startAuto(false);
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        if (hasCameraPermission()) {
            startCamera();
        } else if (!askedPermission) {
            askedPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            showNoPermission();
        }
    }

    @Override
    protected void onPause() {
        wantCamera = false;
        closeCamera();
        if (camThread != null) {
            camHandler.removeCallbacksAndMessages(null);
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // If you said yes, onResume runs right after this and starts the camera.
        if (code == REQ_CAMERA && !hasCameraPermission()) showNoPermission();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showNoPermission() {
        status.setText("This app needs the camera to see your room's colors.");
        captureBtn.setText("Allow camera");
    }

    private void onCapture() {
        if (!hasCameraPermission()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            } else {
                status.setText("Camera is blocked. Open Settings > Apps > Room Color Wallpaper > Permissions and allow Camera.");
            }
            return;
        }
        captureColors();
    }

    private void startCamera() {
        wantCamera = true;
        captureBtn.setText("Capture colors");
        if (preview.isAvailable()) openCameraIfWanted();
        // otherwise the SurfaceTextureListener calls openCameraIfWanted() when ready
    }

    // ---------------------------------------------------------------- camera

    private void openCameraIfWanted() {
        if (!wantCamera || camera != null || camHandler == null || !hasCameraPermission()) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = pickBackCamera(manager);
            if (id == null) { status.setText("No camera found on this device."); return; }
            SurfaceTexture tex = preview.getSurfaceTexture();
            tex.setDefaultBufferSize(1280, 720); // small preview is enough to read colors
            previewSurface = new Surface(tex);
            manager.openCamera(id, cameraCallback, camHandler);
        } catch (CameraAccessException | SecurityException e) {
            status.setText("Could not open the camera: " + e.getMessage());
        }
    }

    private String pickBackCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null; // tablet with only a front camera
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            camera = device;
            try {
                device.createCaptureSession(Arrays.asList(previewSurface), sessionCallback, camHandler);
            } catch (CameraAccessException e) {
                showError("Camera error: " + e.getMessage());
            }
        }
        @Override public void onDisconnected(CameraDevice device) { device.close(); camera = null; }
        @Override public void onError(CameraDevice device, int error) {
            device.close();
            camera = null;
            showError("Camera is busy or unavailable (code " + error + ").");
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession s) {
            if (camera == null) return;
            session = s;
            try {
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(previewSurface);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                s.setRepeatingRequest(b.build(), null, camHandler);
                // First reading happens by itself once brightness has settled.
                if (colors == null) {
                    camHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            runOnUiThread(new Runnable() { @Override public void run() { captureColors(); } });
                        }
                    }, SETTLE_MS);
                }
            } catch (CameraAccessException | IllegalStateException e) {
                showError("Camera error: " + e.getMessage());
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            showError("The camera could not be set up.");
        }
    };

    private void closeCamera() {
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }

    private void showError(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() {
            closeCamera();
            status.setText(msg);
        }});
    }

    // ---------------------------------------------------------------- colors

    /** Takes a tiny copy of what the camera shows and finds the 3 main colors. */
    private void captureColors() {
        if (!preview.isAvailable() || camera == null) {
            status.setText("Camera is not ready yet. Try again in a second.");
            return;
        }
        Bitmap small = preview.getBitmap(64, 48); // 3,072 dots is plenty to judge colors
        if (small == null) return;
        int[] px = new int[64 * 48];
        small.getPixels(px, 0, 64, 0, 0, 64, 48);
        small.recycle();
        int[] found = ColorMaker.mainColors(px);
        if (found == null) {
            status.setText("Too dark to see colors. Turn on a light and tap Capture colors.");
            return;
        }
        colors = found;
        showResult();
        status.setText("These are your room's colors. Pick a style, then set the wallpaper.");
        setWallpaperButtonsEnabled(true);
    }

    /** Shows the 3 color circles and a small preview of the wallpaper. */
    private void showResult() {
        if (colors == null) return;
        swatchRow.removeAllViews();
        for (int c : colors) {
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(c);
            circle.setStroke(dp(2), Color.WHITE);
            dot.setBackground(circle);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.leftMargin = dp(6);
            lp.rightMargin = dp(6);
            swatchRow.addView(dot, lp);
        }
        wallpaperPreview.setImageBitmap(ColorMaker.drawWallpaper(colors, shapesStyle, dp(70), dp(110)));
    }

    // ---------------------------------------------------------------- wallpaper

    /** Sets the wallpaper now, and remembers which screen you picked for the automatic updates. */
    private void setWallpaper(final int which) {
        if (colors == null) return;
        ColorMaker.prefs(this).edit().putInt(ColorMaker.KEY_TARGET, which).apply();
        setWallpaperButtonsEnabled(false);
        status.setText("Setting wallpaper...");
        final int[] c = colors;
        final boolean shapes = shapesStyle;

        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                ColorMaker.apply(MainActivity.this, c, shapes, which);
                msg = "Done! Your " + ColorMaker.targetName(which) + " now matches your room.";
            } catch (Exception e) {
                msg = "Could not set the wallpaper: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(new Runnable() { @Override public void run() {
                status.setText(m);
                setWallpaperButtonsEnabled(true);
                updateAutoButtons();
            }});
        }}).start();
    }

    // ---------------------------------------------------------------- auto every 10 minutes

    private void onAutoButton() {
        SharedPreferences p = ColorMaker.prefs(this);
        boolean on = p.getBoolean(ColorMaker.KEY_AUTO, false);
        if (on) {
            p.edit().putBoolean(ColorMaker.KEY_AUTO, false).apply();
            stopService(new Intent(this, AutoService.class));
            status.setText("Auto is off. Your wallpaper stays as it is.");
        } else {
            int which = p.getInt(ColorMaker.KEY_TARGET, 0);
            if (which == 0) {
                status.setText("First tap Set Home, Set Lock or Set Both, so the app knows which screen to update.");
                return;
            }
            if (!hasCameraPermission()) { showNoPermission(); return; }
            p.edit().putBoolean(ColorMaker.KEY_AUTO, true).apply();
            startAuto(false);
            status.setText("Auto is on. Every 10 minutes your " + ColorMaker.targetName(which)
                    + " will update to match your room. You can close the app.");
        }
        updateAutoButtons();
    }

    private void startAuto(boolean now) {
        Intent i = new Intent(this, AutoService.class).putExtra(AutoService.EXTRA_NOW, now);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            status.setText("Could not start auto updates: " + e.getMessage());
        }
    }

    private void updateAutoButtons() {
        SharedPreferences p = ColorMaker.prefs(this);
        boolean on = p.getBoolean(ColorMaker.KEY_AUTO, false);
        autoBtn.setText(on ? "Auto every 10 min: ON" : "Auto every 10 min: OFF");
        // Only needed to restart by itself after the tablet turns on.
        rebootBtn.setVisibility(on && !Settings.canDrawOverlays(this) ? View.VISIBLE : View.GONE);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            status.setText("Turn on \"Allow display over other apps\", then come back.");
        } catch (Exception e) {
            status.setText("Open Settings > Apps > Room Color Wallpaper and allow \"Display over other apps\".");
        }
    }
}
