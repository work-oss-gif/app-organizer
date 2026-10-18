package com.snaponopen.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Snap On Open: the moment this app opens, it takes a photo with the back camera,
 * saves it to the gallery (Pictures/SnapOnOpen) and shows it on screen.
 *
 * How it works, step by step:
 *  1. onResume (the app comes on screen) -> ask for camera permission if needed.
 *  2. Open the back camera and show a live preview for about 1 second,
 *     so the camera can adjust brightness and focus (otherwise photos come out dark).
 *  3. Take the photo, play the shutter sound, save it, show it.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final long SETTLE_MS = 1000; // time to let brightness/focus adjust

    // Screen parts
    private TextureView preview;
    private ImageView photoView;
    private TextView status;
    private Button button;
    private float density;

    // Camera parts
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private int jpegOrientation;
    private boolean snapped;
    private boolean wantCamera;
    private boolean askedPermission;
    private final MediaActionSound shutter = new MediaActionSound();

    // ---------------------------------------------------------------- screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        shutter.load(MediaActionSound.SHUTTER_CLICK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setVisibility(View.GONE);
        root.addView(photoView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(dp(16), dp(12), dp(16), dp(16));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        bar.addView(status);

        button = new Button(this);
        button.setText("Take another");
        button.setVisibility(View.GONE);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onButton(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(8);
        bar.addView(button, bp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, barLp);
        setContentView(root);

        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCameraIfWanted(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    private int dp(int v) { return (int) (v * density); }

    @Override
    protected void onResume() {
        super.onResume();
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        if (hasCameraPermission()) {
            startSnap();
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
    protected void onDestroy() {
        shutter.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // If you said yes, onResume runs right after this and takes the photo.
        if (code == REQ_CAMERA && !hasCameraPermission()) showNoPermission();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void showNoPermission() {
        status.setText("This app needs the camera to take a photo.");
        button.setText("Allow camera");
        button.setVisibility(View.VISIBLE);
    }

    private void onButton() {
        if (!hasCameraPermission()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            } else {
                status.setText("Camera is blocked. Open Settings > Apps > Snap On Open > Permissions and allow Camera.");
            }
            return;
        }
        startSnap();
    }

    /** Show the live preview and start the camera; the photo is taken automatically. */
    private void startSnap() {
        snapped = false;
        wantCamera = true;
        photoView.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        button.setVisibility(View.GONE);
        status.setText("Taking photo...");
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
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // Biggest photo size the camera supports = best quality.
            Size photoSize = biggest(map.getOutputSizes(ImageFormat.JPEG), Integer.MAX_VALUE);
            reader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { savePhoto(r); }
            }, camHandler);

            // Preview size: up to 1920 wide is plenty for the screen.
            Size pSize = biggest(map.getOutputSizes(SurfaceTexture.class), 1920);
            SurfaceTexture tex = preview.getSurfaceTexture();
            tex.setDefaultBufferSize(pSize.getWidth(), pSize.getHeight());
            previewSurface = new Surface(tex);

            // Which way to turn the photo so it is upright (depends on how you hold the device).
            Integer sensor = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int rotationDeg = getWindowManager().getDefaultDisplay().getRotation() * 90;
            jpegOrientation = ((sensor == null ? 90 : sensor) - rotationDeg + 360) % 360;

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

    private static Size biggest(Size[] sizes, int maxWidth) {
        Size best = sizes[0];
        long bestArea = -1;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (s.getWidth() <= maxWidth && area > bestArea) { best = s; bestArea = area; }
        }
        return best;
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            camera = device;
            try {
                device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                        sessionCallback, camHandler);
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
                // Live preview so auto-brightness and auto-focus can settle...
                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(previewSurface);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                s.setRepeatingRequest(b.build(), null, camHandler);
                // ...then snap automatically.
                camHandler.postDelayed(new Runnable() {
                    @Override public void run() { takePicture(); }
                }, SETTLE_MS);
            } catch (CameraAccessException | IllegalStateException e) {
                showError("Camera error: " + e.getMessage());
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession s) {
            showError("The camera could not be set up.");
        }
    };

    private void takePicture() {
        if (snapped || session == null || camera == null) return;
        snapped = true;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            session.capture(b.build(), null, camHandler);
            shutter.play(MediaActionSound.SHUTTER_CLICK);
        } catch (CameraAccessException | IllegalStateException e) {
            showError("Could not take the photo: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
    }

    // ---------------------------------------------------------------- saving

    /** Runs on the camera thread when the photo arrives. */
    private void savePhoto(ImageReader r) {
        byte[] bytes;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } finally {
            if (img != null) img.close();
        }

        String where;
        try {
            where = writeToGallery(bytes);
        } catch (Exception e) {
            where = null;
            final String msg = e.getMessage();
            runOnUiThread(new Runnable() { @Override public void run() {
                status.setText("Photo taken but could not be saved: " + msg);
            }});
        }

        final Bitmap shown = decodeForScreen(bytes);
        final String savedTo = where;
        runOnUiThread(new Runnable() { @Override public void run() {
            closeCamera(); // photo done, free the camera
            if (shown != null) {
                photoView.setImageBitmap(shown);
                photoView.setVisibility(View.VISIBLE);
                preview.setVisibility(View.GONE);
            }
            if (savedTo != null) status.setText("Saved to " + savedTo);
            button.setText("Take another");
            button.setVisibility(View.VISIBLE);
        }});
    }

    /** Saves the JPEG. Android 10+: into the gallery. Older: into the app's own Pictures folder. */
    private String writeToGallery(byte[] jpeg) throws Exception {
        String name = "Snap_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SnapOnOpen");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("gallery refused the file");
            OutputStream out = cr.openOutputStream(uri);
            try { out.write(jpeg); } finally { out.close(); }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return "Gallery > Pictures/SnapOnOpen";
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(jpeg); } finally { out.close(); }
            return f.getAbsolutePath();
        }
    }

    /** Makes a screen-sized, upright copy of the photo to display. */
    private Bitmap decodeForScreen(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 1600 || o.outHeight / (sample * 2) >= 1600) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (bmp == null) return null;

        // Some cameras store "turn me" as a note inside the photo instead of turning the pixels.
        int deg = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        if (deg == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    private void showError(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() {
            closeCamera();
            status.setText(msg);
            button.setText("Try again");
            button.setVisibility(View.VISIBLE);
        }});
    }
}
