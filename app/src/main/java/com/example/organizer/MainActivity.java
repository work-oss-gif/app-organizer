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

    // ---------- Prediction ("Up next") ----------
    // Every time you open an app from here we write down three simple facts:
    //   c:<app>          how many times you opened it in total
    //   h:<slot>:<app>   how many times you opened it at this time of day (8 slots of 3 hours)
    //   t:<prev>><app>   how many times you opened it right after <prev>
    // To guess the next app we give every app a score from those counts.
    private static final String PREFS = "predict";
    private static final long RECENT_MS = 60 * 60 * 1000L; // "right after" = within 1 hour

    private int slot() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) / 3; }

    private void recordLaunch(String pkg) {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = sp.edit();
        ed.putInt("c:" + pkg, sp.getInt("c:" + pkg, 0) + 1);
        String hk = "h:" + slot() + ":" + pkg;
        ed.putInt(hk, sp.getInt(hk, 0) + 1);
        String last = sp.getString("last", null);
        long lastTime = sp.getLong("lastTime", 0);
        if (last != null && !last.equals(pkg) && System.currentTimeMillis() - lastTime < RECENT_MS) {
            String tk = "t:" + last + ">" + pkg;
            ed.putInt(tk, sp.getInt(tk, 0) + 1);
        }
        ed.putString("last", pkg);
        ed.putLong("lastTime", System.currentTimeMillis());
        ed.apply();
    }

    private static class Guess { App app; double score; String why; }

    private List<Guess> predict(Map<String, App> all) {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = sp.getString("last", null);
        boolean recent = last != null && System.currentTimeMillis() - sp.getLong("lastTime", 0) < RECENT_MS;
        String slotPrefix = "h:" + slot() + ":";
        List<Guess> out = new ArrayList<>();
        for (App a : all.values()) {
            int total = sp.getInt("c:" + a.pkg, 0);
            if (total == 0) continue;
            int after = recent ? sp.getInt("t:" + last + ">" + a.pkg, 0) : 0;
            int now = sp.getInt(slotPrefix + a.pkg, 0);
            Guess g = new Guess();
            g.app = a;
            // "After the last app" matters most, then "usual for this time", then "used a lot".
            g.score = after * 3.0 + now * 1.5 + total * 0.2;
            if (recent && a.pkg.equals(last)) g.score *= 0.3; // you just used it
            App lastApp = recent ? all.get(last) : null;
            if (after > 0 && lastApp != null) g.why = "after " + lastApp.label;
            else if (now > 0) g.why = "usual now";
            else g.why = "used often";
            out.add(g);
        }
        Collections.sort(out, new Comparator<Guess>() {
            public int compare(Guess x, Guess y) { return Double.compare(y.score, x.score); }
        });
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    private void addUpNext(Map<String, App> all, PackageManager pm) {
        TextView h = new TextView(this);
        h.setText("Up next");
        h.setTextColor(Color.parseColor("#FFD54F"));
        h.setTextSize(20);
        h.setPadding(dp(4), dp(4), 0, dp(8));
        root.addView(h);

        List<Guess> guesses = predict(all);
        if (guesses.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Open a few apps from this screen and I'll start guessing which one you need next.");
            t.setTextColor(Color.parseColor("#BBBBBB"));
            t.setTextSize(13);
            t.setPadding(dp(4), 0, 0, dp(8));
            root.addView(t);
        } else {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(4);
            for (Guess g : guesses) {
                LinearLayout c = (LinearLayout) cell(g.app, pm);
                TextView why = new TextView(this);
                why.setText(g.why);
                why.setTextColor(Color.parseColor("#FFD54F"));
                why.setTextSize(10);
                why.setGravity(Gravity.CENTER);
                why.setMaxLines(1);
                why.setEllipsize(android.text.TextUtils.TruncateAt.END);
                why.setWidth(getResources().getDisplayMetrics().widthPixels / 4 - dp(6));
                c.addView(why);
                grid.addView(c);
            }
            root.addView(grid);
        }

        Button reset = new Button(this);
        reset.setText("Reset learning");
        reset.setTextSize(12);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
                Toast.makeText(MainActivity.this, "Forgot everything. Learning starts again.", Toast.LENGTH_SHORT).show();
                build();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        root.addView(reset, lp);
    }

    private void build() {
        root.removeAllViews();
        PackageManager pm = getPackageManager();
        Map<String, App> all = new HashMap<>();

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
            all.put(pkg, a);
        }

        addUpNext(all, pm);

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
                if (i != null) { recordLaunch(a.pkg); startActivity(i); }
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
