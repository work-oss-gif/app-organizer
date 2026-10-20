package com.roomcolorwallpaper.app;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final long SETTLE_MS = 1500; // time to let brightness adjust

    // Screen parts
    private TextureView preview;
    private ImageView wallpaperPreview;
    private TextView status;
    private LinearLayout swatchRow;
    private Button captureBtn, styleBtn, homeBtn, lockBtn, bothBtn;
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
        styleBtn = addButton(row1, "Style: Smooth");
        LinearLayout row2 = buttonRow(panel);
        homeBtn = addButton(row2, "Set Home");
        lockBtn = addButton(row2, "Set Lock");
        bothBtn = addButton(row2, "Set Both");

        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCapture(); }
        });
        styleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                shapesStyle = !shapesStyle;
                styleBtn.setText(shapesStyle ? "Style: Shapes" : "Style: Smooth");
                showResult();
            }
        });
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWallpaper(WallpaperManager.FLAG_SYSTEM, "home screen"); }
        });
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWallpaper(WallpaperManager.FLAG_LOCK, "lock screen"); }
        });
        bothBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setWallpaper(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK, "home and lock screens");
            }
        });
        setWallpaperButtonsEnabled(false);

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
        int[] found = mainColors(small);
        small.recycle();
        if (found == null) {
            status.setText("Too dark to see colors. Turn on a light and tap Capture colors.");
            return;
        }
        colors = found;
        showResult();
        status.setText("These are your room's colors. Pick a style, then set the wallpaper.");
        setWallpaperButtonsEnabled(true);
    }

    /**
     * Groups similar colors into "buckets" (8 shades per red, green and blue = 512 buckets),
     * counts how many dots land in each, then picks the 3 biggest buckets that look
     * clearly different from each other.
     */
    private static int[] mainColors(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        final long[] count = new long[512];
        long[] sumR = new long[512], sumG = new long[512], sumB = new long[512];
        long bright = 0;
        for (int c : px) {
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            bright += r + g + b;
            int key = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
            count[key]++;
            sumR[key] += r; sumG[key] += g; sumB[key] += b;
        }
        if (bright / (px.length * 3L) < 12) return null; // camera covered or room dark

        List<Integer> keys = new ArrayList<Integer>();
        for (int k = 0; k < 512; k++) if (count[k] > 0) keys.add(k);
        Collections.sort(keys, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Long.compare(count[b], count[a]); }
        });

        List<Integer> picked = new ArrayList<Integer>();
        for (int k : keys) {
            int col = Color.rgb((int) (sumR[k] / count[k]), (int) (sumG[k] / count[k]), (int) (sumB[k] / count[k]));
            boolean different = true;
            for (int p : picked) if (distance(p, col) < 70) { different = false; break; }
            if (different) picked.add(col);
            if (picked.size() == 3) break;
        }
        // Plain room with fewer than 3 colors: add a lighter and a darker shade of the main one.
        while (picked.size() < 3) {
            int base = picked.get(0);
            picked.add(picked.size() == 1 ? mix(base, Color.WHITE, 0.35f) : mix(base, Color.BLACK, 0.35f));
        }
        return new int[]{picked.get(0), picked.get(1), picked.get(2)};
    }

    private static double distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
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
        wallpaperPreview.setImageBitmap(drawWallpaper(dp(70), dp(110)));
    }

    // ---------------------------------------------------------------- wallpaper

    /** Draws the wallpaper picture from the 3 colors. */
    private Bitmap drawWallpaper(int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (!shapesStyle) {
            // Smooth: one color fades into the next, from top-left to bottom-right.
            paint.setShader(new LinearGradient(0, 0, w, h, colors, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        } else {
            // Shapes: main color as background, the other two as big soft glowing circles.
            canvas.drawColor(colors[0]);
            float big = Math.max(w, h);
            drawGlow(canvas, paint, w * 0.25f, h * 0.30f, big * 0.55f, colors[1]);
            drawGlow(canvas, paint, w * 0.80f, h * 0.75f, big * 0.50f, colors[2]);
            drawGlow(canvas, paint, w * 0.70f, h * 0.15f, big * 0.25f, mix(colors[1], colors[2], 0.5f));
        }
        return out;
    }

    private static void drawGlow(Canvas c, Paint p, float x, float y, float radius, int color) {
        int clear = color & 0x00FFFFFF; // same color but fully see-through
        p.setShader(new RadialGradient(x, y, radius, color, clear, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, p);
    }

    /** Draws a full-size wallpaper in the background and sets it. */
    private void setWallpaper(final int which, final String name) {
        if (colors == null) return;
        setWallpaperButtonsEnabled(false);
        status.setText("Setting wallpaper...");

        // Square, as big as the longest side of the screen, so it fits when you turn the tablet.
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int size = Math.max(dm.widthPixels, dm.heightPixels);

        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                Bitmap full = drawWallpaper(size, size);
                WallpaperManager.getInstance(MainActivity.this).setBitmap(full, null, true, which);
                full.recycle();
                msg = "Done! Your " + name + " now matches your room.";
            } catch (Exception e) {
                msg = "Could not set the wallpaper: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(new Runnable() { @Override public void run() {
                status.setText(m);
                setWallpaperButtonsEnabled(true);
            }});
        }}).start();
    }
}
