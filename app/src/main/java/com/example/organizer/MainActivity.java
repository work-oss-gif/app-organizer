package com.example.organizer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private LinearLayout root;

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

        for (Map.Entry<String, List<App>> e : groups.entrySet()) {
            List<App> apps = e.getValue();
            if (apps.isEmpty()) continue;
            Collections.sort(apps, new Comparator<App>() {
                public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
            });
            TextView h = new TextView(this);
            h.setText(e.getKey() + " (" + apps.size() + ")");
            h.setTextColor(Color.parseColor("#82B1FF"));
            h.setTextSize(18);
            h.setPadding(dp(4), dp(20), 0, dp(8));
            root.addView(h);
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
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                return true;
            }
        });
        return c;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
