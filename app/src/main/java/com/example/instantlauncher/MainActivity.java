package com.example.instantlauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Instant Launcher: a home screen that opens an app the MOMENT your finger
 * touches its icon (finger DOWN), instead of waiting for you to lift it (finger UP).
 *
 * The screen never scrolls. Apps are split into pages, and you switch pages with
 * the arrow buttons at the bottom. That matters: if the screen scrolled, putting
 * your finger down to scroll would accidentally open an app.
 */
public class MainActivity extends Activity {

    private float density;
    private int page = 0;
    private boolean editMode = false;      // when ON, touching an icon opens its App Info instead
    private long lastLaunchTime = 0;       // stops two fingers from opening two apps at once

    private final List<ResolveInfo> apps = new ArrayList<>();

    private int dp(float v) { return (int) (v * density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
    }

    // Rebuild every time the screen shows, so newly installed apps appear.
    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        buildScreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Pressing Home while already here jumps back to page 1.
        page = 0;
        editMode = false;
    }

    // Tablet turned sideways or upright: redraw so the grid fits the new shape.
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildScreen();
    }

    // Home screens should ignore the Back button.
    @Override
    public void onBackPressed() { }

    /** Collect every app that has an icon in the app drawer, sorted A to Z. */
    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (!ri.activityInfo.packageName.equals(getPackageName())) apps.add(ri);
        }
        Collections.sort(apps, (a, b) ->
                a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
    }

    /** Draw the whole screen: the grid of icons for this page, plus the bottom bar. */
    private void buildScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cellW = dp(96), cellH = dp(104), barH = dp(64), topPad = dp(32);

        // How many icons fit on screen without scrolling.
        int cols = Math.max(1, dm.widthPixels / cellW);
        int rows = Math.max(1, (dm.heightPixels - barH - topPad) / cellH);
        int perPage = cols * rows;
        int pageCount = Math.max(1, (apps.size() + perPage - 1) / perPage);
        if (page >= pageCount) page = pageCount - 1;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(editMode ? Color.rgb(60, 20, 20) : Color.rgb(18, 18, 24));
        root.setPadding(0, topPad, 0, 0);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        grid.setLayoutParams(gridLp);

        int start = page * perPage;
        int end = Math.min(apps.size(), start + perPage);
        for (int i = start; i < end; i++) {
            grid.addView(makeIcon(apps.get(i), cellW, cellH));
        }
        root.addView(grid);
        root.addView(makeBottomBar(pageCount, barH));
        setContentView(root);
    }

    /** One app icon + name. The magic happens in the touch listener. */
    private View makeIcon(final ResolveInfo app, int w, int h) {
        PackageManager pm = getPackageManager();

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = w;
        lp.height = h;
        cell.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        Drawable d = app.loadIcon(pm);
        icon.setImageDrawable(d);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView label = new TextView(this);
        label.setText(app.loadLabel(pm));
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setPadding(dp(4), dp(4), dp(4), 0);
        cell.addView(label, new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT));

        final String pkg = app.activityInfo.packageName;

        cell.setOnTouchListener((v, event) -> {
            // ACTION_DOWN = the very first instant your finger touches the glass.
            // A normal button waits for ACTION_UP (finger lifted). We don't wait.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.4f);   // quick visual "I felt that"
                if (editMode) openAppInfo(pkg);
                else launchNow(pkg);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
            }
            return true;   // "I handled this touch" so nothing else reacts to it
        });
        return cell;
    }

    /** Open an app immediately, and skip the zoom-in animation so it appears faster. */
    private void launchNow(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunchTime < 600) return;   // ignore a second finger landing right after
        lastLaunchTime = now;

        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 0, 0 = no opening animation. The app shows up right away.
        ActivityOptions noAnim = ActivityOptions.makeCustomAnimation(this, 0, 0);
        startActivity(launch, noAnim.toBundle());
    }

    /** Android's own "App info" page for an app (to uninstall, clear data, etc.). */
    private void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + pkg));
        startActivity(i);
    }

    /** Bottom bar: previous page, page number, edit-mode switch, next page. */
    private View makeBottomBar(int pageCount, int barH) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.rgb(30, 30, 40));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barH));

        // These buttons use a normal tap (finger lifted), on purpose,
        // so you can't change pages by accident.
        Button prev = new Button(this);
        prev.setText("◀");
        prev.setEnabled(page > 0);
        prev.setOnClickListener(v -> { page--; buildScreen(); });

        TextView pageText = new TextView(this);
        pageText.setText("Page " + (page + 1) + " of " + pageCount);
        pageText.setTextColor(Color.WHITE);
        pageText.setPadding(dp(16), 0, dp(16), 0);

        Button edit = new Button(this);
        edit.setText(editMode ? "Done" : "App info mode");
        edit.setOnClickListener(v -> {
            editMode = !editMode;
            Toast.makeText(this, editMode
                    ? "Touch an app to see its App Info"
                    : "Back to instant launching", Toast.LENGTH_SHORT).show();
            buildScreen();
        });

        Button next = new Button(this);
        next.setText("▶");
        next.setEnabled(page < pageCount - 1);
        next.setOnClickListener(v -> { page++; buildScreen(); });

        bar.addView(prev);
        bar.addView(pageText);
        bar.addView(edit);
        bar.addView(next);
        return bar;
    }
}
