package com.roomcolorwallpaper.app;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The shared "brain" of the app, used by both the screen (MainActivity)
 * and the background helper (AutoService):
 *  - finds the 3 main colors in a picture
 *  - draws a wallpaper from them
 *  - sets it as the wallpaper
 *  - remembers your choices (which screen, which style, auto on/off)
 */
public final class ColorMaker {

    private ColorMaker() { }

    // ---------------------------------------------------------------- saved choices

    static final String PREFS = "room_color_wallpaper";
    static final String KEY_TARGET = "target";       // which screen: home, lock or both (0 = not chosen yet)
    static final String KEY_SHAPES = "shapes";       // false = Smooth style, true = Shapes style
    static final String KEY_AUTO = "auto";           // update every 10 minutes?
    static final String KEY_LAST_COLORS = "last_colors";
    static final String KEY_LAST_TIME = "last_time";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String targetName(int which) {
        boolean home = (which & WallpaperManager.FLAG_SYSTEM) != 0;
        boolean lock = (which & WallpaperManager.FLAG_LOCK) != 0;
        if (home && lock) return "home and lock screens";
        if (lock) return "lock screen";
        return "home screen";
    }

    // ---------------------------------------------------------------- finding colors

    /**
     * Groups similar colors into "buckets" (8 shades per red, green and blue = 512 buckets),
     * counts how many dots land in each, then picks the 3 biggest buckets that look
     * clearly different from each other. Returns null if the picture is too dark.
     */
    static int[] mainColors(int[] px) {
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
        if (bright / (px.length * 3L) < 12) return null; // camera covered, face down, or room dark

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

    static double distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** True if the new colors are almost the same as last time (then there is no need to change the wallpaper). */
    static boolean sameAsLast(Context c, int[] colors) {
        String last = prefs(c).getString(KEY_LAST_COLORS, null);
        if (last == null) return false;
        String[] parts = last.split(",");
        if (parts.length != 3) return false;
        try {
            for (int i = 0; i < 3; i++) {
                if (distance(Integer.parseInt(parts[i]), colors[i]) > 25) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the wallpaper picture from the 3 colors. */
    static Bitmap drawWallpaper(int[] colors, boolean shapes, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (!shapes) {
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

    // ---------------------------------------------------------------- setting the wallpaper

    /**
     * Draws a full-size wallpaper and sets it on the chosen screen(s), then remembers the colors.
     * Slow (about a second), so call it from a background thread.
     */
    static void apply(Context c, int[] colors, boolean shapes, int which) throws Exception {
        // Square, as big as the longest side of the screen, so it fits when you turn the tablet.
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int size = Math.max(dm.widthPixels, dm.heightPixels);

        Bitmap full = drawWallpaper(colors, shapes, size, size);
        try {
            WallpaperManager.getInstance(c).setBitmap(full, null, true, which);
        } finally {
            full.recycle();
        }
        prefs(c).edit()
                .putString(KEY_LAST_COLORS, colors[0] + "," + colors[1] + "," + colors[2])
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .apply();
    }
}
