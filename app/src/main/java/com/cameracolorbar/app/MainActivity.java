package com.cameracolorbar.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The setup screen. It walks the user through the two permissions
 * (camera + "display over other apps") and has Start / Stop buttons.
 * The real work happens in ColorBarService.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private float density;
    private TextView statusText;

    private int dp(float v) {
        return (int) (v * density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(32), dp(20), dp(20));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("Camera Color Bar");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView info = new TextView(this);
        info.setText("Paints the top status bar with the color the back camera sees "
                + "(the middle of the picture). It keeps working in every app until you press Stop.\n\n"
                + "Heads-up: the camera stays on, so this uses a lot of battery. "
                + "The camera turns off by itself while the screen is off.");
        info.setTextSize(15);
        info.setPadding(0, dp(8), 0, dp(16));
        col.addView(info);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusText.setBackgroundColor(Color.argb(30, 128, 128, 128));
        col.addView(statusText);

        col.addView(button("1. Allow camera and notifications", new View.OnClickListener() {
            public void onClick(View v) { askPermissions(); }
        }));
        col.addView(button("2. Allow \"Display over other apps\"", new View.OnClickListener() {
            public void onClick(View v) { openOverlaySettings(); }
        }));
        col.addView(button("Start", new View.OnClickListener() {
            public void onClick(View v) { startBar(); }
        }));
        col.addView(button("Stop", new View.OnClickListener() {
            public void onClick(View v) { stopBar(); }
        }));

        setContentView(scroll);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void refreshStatus() {
        String s = "Camera permission: " + (hasCamera() ? "allowed ✓" : "not allowed yet")
                + "\nDisplay over other apps: " + (hasOverlay() ? "allowed ✓" : "not allowed yet")
                + "\nColor bar: " + (ColorBarService.running ? "ON" : "off");
        statusText.setText(s);
    }

    private void askPermissions() {
        List<String> perms = new ArrayList<>();
        if (!hasCamera()) perms.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (perms.isEmpty()) {
            toast("Already allowed");
            return;
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshStatus();
    }

    private void openOverlaySettings() {
        if (hasOverlay()) {
            toast("Already allowed");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
        toast("Find Camera Color Bar and turn it on, then come back");
    }

    private void startBar() {
        if (!hasCamera()) { toast("Do step 1 first (camera)"); return; }
        if (!hasOverlay()) { toast("Do step 2 first (display over other apps)"); return; }
        Intent i = new Intent(this, ColorBarService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            toast("Started. Point the back camera at something colorful.");
        } catch (Exception e) {
            toast("Could not start: " + e.getMessage());
        }
        statusText.postDelayed(new Runnable() {
            public void run() { refreshStatus(); }
        }, 500);
    }

    private void stopBar() {
        stopService(new Intent(this, ColorBarService.class));
        statusText.postDelayed(new Runnable() {
            public void run() { refreshStatus(); }
        }, 300);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
