package com.example.organizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String EXTRA_CATEGORY = "category";
    private LinearLayout root;
    private String filter; // when set, only this category is shown

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#121212"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, dp(32), p, p);
        sv.addView(root);
        setContentView(sv);
        filter = getIntent().getStringExtra(EXTRA_CATEGORY);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // A plain launch (home button) shows everything; a category shortcut shows just that one.
        filter = i.getStringExtra(EXTRA_CATEGORY);
    }

    @Override public void onBackPressed() {
        if (filter != null) { filter = null; build(); } else super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private static class App { String label; String pkg; android.graphics.drawable.Drawable icon; }

    private void build() {
        root.removeAllViews();
        PackageManager pm = getPackageManager();

        Set<String> browsers = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        for (ResolveInfo r : pm.queryIntentActivities(web, 0)) browsers.add(r.activityInfo.packageName);

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, List<App>> groups = new LinkedHashMap<>();
        for (String g : new String[]{"Browsers", "Games", "Social", "Media", "Productivity", "Tools", "Other"})
            groups.put(g, new ArrayList<App>());

        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = r.loadLabel(pm).toString();
            a.icon = r.loadIcon(pm);
            groups.get(categorize(r.activityInfo.applicationInfo, browsers.contains(pkg))).add(a);
        }

        if (filter != null) {
            TextView back = new TextView(this);
            back.setText("\u2190 All apps");
            back.setTextColor(Color.WHITE);
            back.setTextSize(16);
            back.setPadding(dp(4), 0, 0, dp(4));
            back.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { filter = null; build(); }
            });
            root.addView(back);
        }

        for (final Map.Entry<String, List<App>> e : groups.entrySet()) {
            final List<App> apps = e.getValue();
            if (apps.isEmpty()) continue;
            if (filter != null && !filter.equals(e.getKey())) continue;
            Collections.sort(apps, new Comparator<App>() {
                public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
            });
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(4), dp(20), 0, dp(8));
            TextView h = new TextView(this);
            h.setText(e.getKey() + " (" + apps.size() + ")");
            h.setTextColor(Color.parseColor("#82B1FF"));
            h.setTextSize(18);
            header.addView(h, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button pin = new Button(this);
            pin.setText("Add to home screen");
            pin.setAllCaps(false);
            pin.setTextSize(12);
            pin.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { addCategoryToHomeScreen(e.getKey(), apps); }
            });
            header.addView(pin);
            root.addView(header);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(4);
            for (App a : apps) grid.addView(cell(a, pm));
            root.addView(grid);
        }
    }

    private String categorize(ApplicationInfo ai, boolean isBrowser) {
        if (isBrowser) return "Browsers";
        if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return "Games";
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE: return "Media";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
            case ApplicationInfo.CATEGORY_NEWS: return "Productivity";
            case ApplicationInfo.CATEGORY_MAPS:
            case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "Tools";
            default: return "Other";
        }
    }

    private View cell(final App a, PackageManager pm) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int w = getResources().getDisplayMetrics().widthPixels / 4 - dp(6);
        c.setLayoutParams(new GridLayout.LayoutParams());
        c.setPadding(dp(2), dp(8), dp(2), dp(8));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        c.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText(a.label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setWidth(w);
        c.addView(t);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
                if (i != null) startActivity(i);
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showAppMenu(a);
                return true;
            }
        });
        return c;
    }

    private void showAppMenu(final App a) {
        new AlertDialog.Builder(this)
            .setTitle(a.label)
            .setPositiveButton("Add to home screen", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { addToHomeScreen(a); }
            })
            .setNeutralButton("App info", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addToHomeScreen(App a) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (launch == null) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            return;
        }
        pinShortcut("app-" + a.pkg, a.label, launch, toBitmap(a.icon));
    }

    private void addCategoryToHomeScreen(String category, List<App> apps) {
        Intent open = new Intent(this, MainActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.putExtra(EXTRA_CATEGORY, category);
        pinShortcut("cat-" + category, category, open, categoryIcon(apps));
    }

    private void pinShortcut(String id, String label, Intent launch, Bitmap bmp) {
        if (Build.VERSION.SDK_INT >= 26) {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                Toast.makeText(this, "Your home screen app doesn't support adding shortcuts", Toast.LENGTH_LONG).show();
                return;
            }
            ShortcutInfo info = new ShortcutInfo.Builder(this, id)
                .setShortLabel(label)
                .setIcon(Icon.createWithBitmap(bmp))
                .setIntent(launch)
                .build();
            sm.requestPinShortcut(info, null);
        } else {
            Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
            sendBroadcast(add);
            Toast.makeText(this, "Shortcut added", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap toBitmap(Drawable d) {
        int size = dp(96);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Rect old = d.copyBounds();
        d.setBounds(0, 0, size, size);
        d.draw(c);
        d.setBounds(old);
        return bmp;
    }

    /** Rounded dark tile with up to four of the category's app icons in a 2x2 grid. */
    private Bitmap categoryIcon(List<App> apps) {
        int size = dp(96), pad = dp(10), gap = dp(6);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.parseColor("#2A2F3A"));
        c.drawRoundRect(new RectF(0, 0, size, size), dp(22), dp(22), paint);
        int cell = (size - 2 * pad - gap) / 2;
        for (int i = 0; i < Math.min(4, apps.size()); i++) {
            int x = pad + (i % 2) * (cell + gap), y = pad + (i / 2) * (cell + gap);
            Drawable d = apps.get(i).icon;
            android.graphics.Rect old = d.copyBounds();
            d.setBounds(x, y, x + cell, y + cell);
            d.draw(c);
            d.setBounds(old);
        }
        return bmp;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
