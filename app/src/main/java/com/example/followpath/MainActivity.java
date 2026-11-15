package com.example.followpath;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Follow the Path
 * A winding road flows DOWN the screen. Keep your finger on it.
 * Slip off the road or lift your finger = game over.
 * The road is the SAME every time, so you can learn it and beat your best score.
 * Modes: Auto speed, Finger height controls speed, Second finger on a slider controls speed.
 * In the two control modes, traffic lights before turns: green = any speed, yellow = medium, red = slow.
 */
public class MainActivity extends Activity {

    private GameView game;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = new GameView(this);
        setContentView(game);
        applyBars();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyBars();          // dark mode switched while app is open
        game.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        game.pauseToGameOver();   // leaving the app mid-run ends the run
    }

    boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    int bgColor() { return isDark() ? 0xFF121417 : 0xFFF5F6F8; }

    /** Colors the status bar and hides the navigation bar so a swipe near the bottom doesn't go Home. */
    private void applyBars() {
        Window w = getWindow();
        w.setStatusBarColor(bgColor());
        w.setNavigationBarColor(bgColor());
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= 23 && !isDark()) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------------------------------


    class GameView extends View {
        static final int READY = 0, PLAYING = 1, OVER = 2;
        static final int AUTO = 0, HEIGHT = 1, SLIDER = 2;     // the three modes
        static final float GRAVITY = 22f;   // in control modes the road never goes slower than this
        static final int GREEN = 0, YELLOW = 1, RED = 2;
        int state = READY;

        final float dp = getResources().getDisplayMetrics().density;
        final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint btn = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path shape = new Path();
        final RectF modeBtn = new RectF();
        final RectF sideBtn = new RectF();
        final RectF sizeBtn = new RectF();
        final RectF colorBtn = new RectF();

        // path size and color options
        final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};
        final float[] SIZE_MUL = {0.75f, 1f, 1.3f, 1.6f};
        final String[] COLOR_NAMES = {"Green", "Blue", "Purple", "Orange", "Pink", "Gray"};
        // each color: road (light mode), edge (light mode), road (dark mode), edge (dark mode)
        final int[][] COLORS = {
                {0xFF7FD3A6, 0xFF2E9E6A, 0xFF2E7D5B, 0xFF4CC38A},
                {0xFF90C2F9, 0xFF1A73E8, 0xFF1F4E8C, 0xFF5B9BF0},
                {0xFFC9A7F5, 0xFF8E44D9, 0xFF4B2C7A, 0xFFA97BEA},
                {0xFFFFC48A, 0xFFE37400, 0xFF8A4B12, 0xFFFF9E40},
                {0xFFF7A8C8, 0xFFD63384, 0xFF7A2550, 0xFFF06AA6},
                {0xFFC4C8CE, 0xFF6B7280, 0xFF3C424B, 0xFF8A93A0}};
        int sizeIdx, colorIdx;
        boolean sliderLeft;         // option: slider on the left side instead of the right
        final RectF tmp = new RectF();

        int mode;
        float scroll = 0;          // how far the road has moved (in dp)
        float speed;               // dp per second
        long lastTime;
        float fingerX = -1, fingerY = -1;
        int pointerId = -1;         // the finger on the road
        int sliderId = -1;          // the second finger on the slider
        float sliderY = -1;
        int score = 0, best;
        boolean newBest = false;
        String overReason = "";
        long overAt = 0;
        long lastWarn = 0;
        boolean warning = false;

        // stop lights: positions along the road (dp), found once, always the same
        final java.util.ArrayList<Float> lights = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> lightColor = new java.util.ArrayList<>();   // GREEN, YELLOW or RED
        float lightsScannedTo = 600;

        // speed limit option: {yellow limit, red limit} for Relaxed, Normal, Strict
        final String[] LIMIT_NAMES = {"Relaxed", "Normal", "Strict"};
        final float[][] LIMITS = {{150, 80}, {100, 50}, {70, 35}};
        int limitIdx;
        final RectF limitBtn = new RectF();

        /** Highest speed allowed when passing a light of this color. Green has no limit. */
        float limitFor(int color) {
            if (color == RED) return LIMITS[limitIdx][1];
            if (color == YELLOW) return LIMITS[limitIdx][0];
            return Float.MAX_VALUE;
        }
        int nextLight = 0;          // index of the next light the finger has not passed yet

        final SharedPreferences prefs = getSharedPreferences("game", Context.MODE_PRIVATE);

        GameView(Context c) {
            super(c);
            mode = prefs.getInt("mode", prefs.getBoolean("fingerSpeed", false) ? HEIGHT : AUTO);
            best = prefs.getInt(bestKey(), 0);
            sliderLeft = prefs.getBoolean("sliderLeft", false);
            sizeIdx = prefs.getInt("pathSize", 1);
            colorIdx = prefs.getInt("pathColor", 0);
            limitIdx = prefs.getInt("limits", 1);
            road.setStyle(Paint.Style.STROKE);
            road.setStrokeCap(Paint.Cap.ROUND);
            road.setStrokeJoin(Paint.Join.ROUND);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setStrokeJoin(Paint.Join.ROUND);
            mid.setStyle(Paint.Style.STROKE);
            mid.setStrokeWidth(2 * dp);
            text.setTextAlign(Paint.Align.CENTER);
        }

        String bestKey() { return mode == HEIGHT ? "best_finger" : mode == SLIDER ? "best_slider" : "best"; }
        String modeName() { return mode == HEIGHT ? "Finger height" : mode == SLIDER ? "Second finger slider" : "Auto speed"; }
        String modeHelp() {
            if (mode == HEIGHT) return "Finger up = faster, down = slower";
            if (mode == SLIDER) return "2nd finger on the " + (sliderLeft ? "left" : "right") + " strip: up = faster, down = slower";
            return "Speeds up slowly by itself";
        }
        boolean hasLights() { return mode != AUTO; }

        // ---------- the pattern (always the same) ----------

        /** Center of the road at distance d (dp), as a fraction 0..1 of the play area width. Same every game. */
        float roadX(float d) {
            double curve = 0.5
                    + 0.22 * Math.sin(d / 260.0)
                    + 0.12 * Math.sin(d / 113.0 + 1.3)
                    + 0.05 * Math.sin(d / 47.0 + 2.1);
            // Every 1500 dp: 0-650 curvy, 650-900 bends into a straight, 900-1250 STRAIGHT, 1250-1500 bends back.
            final double P = 1500;
            double p = ((d % P) + P) % P;
            long k = (long) Math.floor(d / P);
            double hold = 0.5 + 0.22 * Math.sin(k * 2.3 + 0.7);
            double s;
            if (p < 650) s = 1;
            else if (p < 900) s = 1 - smooth((p - 650) / 250);
            else if (p < 1250) s = 0;
            else s = smooth((p - 1250) / 250);
            double x = s * curve + (1 - s) * hold;
            return (float) Math.max(0.12, Math.min(0.88, x));
        }

        double smooth(double t) { return t * t * (3 - 2 * t); }

        /** In slider mode the right side of the screen is the slider, so the road stays to the left of it. */
        float sliderW() { return 76 * dp; }
        float playW() { return mode == SLIDER ? getWidth() - sliderW() - 8 * dp : getWidth(); }
        /** Where the road area starts: after the slider when the slider is on the left. */
        float playLeft() { return mode == SLIDER && sliderLeft ? sliderW() + 8 * dp : 0; }
        float roadPx(float d) { return playLeft() + roadX(d) * playW(); }

        /** Road width in dp: starts wide, slowly gets narrower. The size option makes it all bigger or smaller. */
        float roadWidth() { return Math.max(46f, 84f - scroll / 250f) * SIZE_MUL[sizeIdx]; }
        float distAt(float y) { return scroll + (getHeight() - y) / dp; }
        float yOf(float d) { return getHeight() - (d - scroll) * dp; }
        float startY() { return getHeight() * 0.72f; }

        /** How sharply the road bends in the 120 dp after d (as a fraction of the road area's width). */
        float sharpAt(float d) {
            float x0 = roadX(d), sharp = 0;
            for (int i = 1; i <= 12; i++) sharp = Math.max(sharp, Math.abs(roadX(d + i * 10) - x0));
            return sharp;
        }

        /** Find the turns ahead and put a traffic light just before each one.
         *  Its color is set by the sharpest part of that turn: gentle = green, medium = yellow, sharp = red. */
        void scanLights(float upTo) {
            float d = lightsScannedTo;
            float last = lights.isEmpty() ? -1e9f : lights.get(lights.size() - 1) + 120;
            while (d < upTo) {
                if (d - last > 700 && sharpAt(d) > 0.12f) {
                    float peak = 0;
                    for (int j = 0; j < 16; j++) peak = Math.max(peak, sharpAt(d + j * 20));
                    lights.add(d - 120);
                    lightColor.add(peak > 0.28f ? RED : peak > 0.2f ? YELLOW : GREEN);
                    last = d;
                }
                d += 20;
            }
            lightsScannedTo = d;
        }

        // ---------- game loop ----------

        void start() {
            state = PLAYING;
            scroll = 0;
            speed = mode == AUTO ? 65 : GRAVITY;
            score = 0;
            newBest = false;
            warning = false;
            sliderId = -1;
            nextLight = 0;
            lastTime = System.nanoTime();
            postInvalidateOnAnimation();
        }

        void gameOver(String why) {
            if (state != PLAYING) return;
            state = OVER;
            overReason = why;
            overAt = System.currentTimeMillis();
            if (score > best) { best = score; newBest = true; prefs.edit().putInt(bestKey(), best).apply(); }
            vibrate(250);
            invalidate();
        }

        void pauseToGameOver() { if (state == PLAYING) gameOver("You left the game"); }

        void vibrate(long ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        /** 0 = middle of road, 1 = right at the edge, above 1 = off the road. */
        float edgeRatio(float x, float y) {
            return Math.abs(x - roadPx(distAt(y))) / ((roadWidth() / 2f + 6f) * dp);
        }

        boolean onRoad(float x, float y) { return edgeRatio(x, y) <= 1f; }

        void sliderRect(RectF r) {
            float h = getHeight();
            if (sliderLeft) r.set(6 * dp, h * 0.22f, sliderW(), h * 0.94f);
            else r.set(getWidth() - sliderW(), h * 0.22f, getWidth() - 6 * dp, h * 0.94f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (state == READY) {
                if (action == MotionEvent.ACTION_DOWN && modeBtn.contains(e.getX(), e.getY())) {
                    mode = (mode + 1) % 3;
                    prefs.edit().putInt("mode", mode).apply();
                    best = prefs.getInt(bestKey(), 0);
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN && hasLights() && limitBtn.contains(e.getX(), e.getY())) {
                    limitIdx = (limitIdx + 1) % LIMIT_NAMES.length;
                    prefs.edit().putInt("limits", limitIdx).apply();
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN && sizeBtn.contains(e.getX(), e.getY())) {
                    sizeIdx = (sizeIdx + 1) % SIZE_NAMES.length;
                    prefs.edit().putInt("pathSize", sizeIdx).apply();
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN && colorBtn.contains(e.getX(), e.getY())) {
                    colorIdx = (colorIdx + 1) % COLOR_NAMES.length;
                    prefs.edit().putInt("pathColor", colorIdx).apply();
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN && mode == SLIDER && sideBtn.contains(e.getX(), e.getY())) {
                    sliderLeft = !sliderLeft;
                    prefs.edit().putBoolean("sliderLeft", sliderLeft).apply();
                    invalidate();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN && onRoad(e.getX(), e.getY())
                        && Math.abs(e.getY() - startY()) < 70 * dp) {
                    pointerId = e.getPointerId(0);
                    fingerX = e.getX(); fingerY = e.getY();
                    start();
                }
                return true;
            }
            if (state == OVER) {
                if (action == MotionEvent.ACTION_DOWN && System.currentTimeMillis() - overAt > 500) {
                    state = READY; scroll = 0; invalidate();
                }
                return true;
            }
            // PLAYING
            if (action == MotionEvent.ACTION_CANCEL) { gameOver("Your finger came off the road"); return true; }
            int ai = e.getActionIndex();
            int aid = e.getPointerId(ai);
            if (action == MotionEvent.ACTION_POINTER_DOWN && mode == SLIDER && sliderId == -1) {
                sliderRect(tmp);
                if (tmp.contains(e.getX(ai), e.getY(ai))) { sliderId = aid; sliderY = e.getY(ai); }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                if (aid == pointerId) { gameOver("You lifted your finger"); return true; }
                if (aid == sliderId) sliderId = -1;
            }
            int idx = e.findPointerIndex(pointerId);
            if (idx >= 0) { fingerX = e.getX(idx); fingerY = e.getY(idx); }
            int sidx = sliderId == -1 ? -1 : e.findPointerIndex(sliderId);
            if (sidx >= 0) sliderY = e.getY(sidx);
            return true;
        }

        /** Throttle from -1 (full brake) to +1 (full gas), from a finger's height compared to a middle line. */
        float throttle(float y, float middle, float range) {
            float t = (middle - y) / range;
            t = Math.max(-1f, Math.min(1f, t));
            return Math.abs(t) < 0.06f ? 0 : t;   // small dead zone in the middle
        }

        void update() {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - lastTime) / 1e9f);
            lastTime = now;
            scroll += speed * dt;

            float t = 0;
            if (mode == AUTO) {
                speed = Math.min(400, speed + 2.5f * dt);
            } else {
                if (mode == HEIGHT) t = throttle(fingerY, startY(), getHeight() * 0.4f);
                else if (sliderId != -1) {
                    sliderRect(tmp);
                    t = throttle(sliderY, tmp.centerY(), tmp.height() / 2f);
                }
                float accel = t > 0 ? t * 140f : t * 260f;   // braking is stronger than speeding up
                speed = Math.max(GRAVITY, Math.min(450, speed + accel * dt));
            }
            score = (int) (scroll / 10);

            float r = edgeRatio(fingerX, fingerY);
            if (r > 1f) { gameOver("You slipped off the road"); return; }
            warning = r > 0.7f;
            long ms = System.currentTimeMillis();
            if (warning && ms - lastWarn > 160) { lastWarn = ms; vibrate(35); }

            // stop lights: when a light reaches your finger, you must be slow enough
            if (hasLights()) {
                scanLights(distAt(-200 * dp) + 200);
                float fingerDist = distAt(fingerY);
                while (nextLight < lights.size() && lights.get(nextLight) <= fingerDist) {
                    int col = lightColor.get(nextLight);
                    if (speed > limitFor(col)) {
                        gameOver(col == RED ? "You ran a red light!" : "Too fast through a yellow light!");
                        return;
                    }
                    nextLight++;
                    vibrate(20);
                }
            }
        }

        // ---------- drawing ----------

        @Override
        protected void onDraw(Canvas c) {
            boolean dark = isDark();
            int bg = bgColor();
            int[] pc = COLORS[colorIdx];
            int roadCol = dark ? pc[2] : pc[0];
            int edgeCol = dark ? pc[3] : pc[1];
            int midCol = dark ? 0x66FFFFFF : 0x88FFFFFF;
            int txt = dark ? 0xFFE8EAED : 0xFF1B1D21;
            int sub = dark ? 0xFF9AA0A6 : 0xFF5F6368;
            int bad = dark ? 0xFFFF6B6B : 0xFFD93025;
            int warnCol = dark ? 0xFFFFB74D : 0xFFE37400;
            int w = getWidth(), h = getHeight();

            if (state == PLAYING) update();

            c.drawColor(bg);

            // the road
            shape.reset();
            float step = 6 * dp;
            for (float y = -step; y <= h + step; y += step) {
                float x = roadPx(distAt(y));
                if (y < 0) shape.moveTo(x, y); else shape.lineTo(x, y);
            }
            float rw = roadWidth() * dp;
            edge.setColor(edgeCol); edge.setStrokeWidth(rw + 6 * dp);
            road.setColor(roadCol); road.setStrokeWidth(rw);
            mid.setColor(midCol);
            c.drawPath(shape, edge);
            c.drawPath(shape, road);
            c.drawPath(shape, mid);

            // stop lights next to the road
            if (hasLights() && state != READY) {
                scanLights(distAt(-200 * dp) + 200);
                for (int i = 0; i < lights.size(); i++) {
                    float ly = yOf(lights.get(i));
                    if (ly < -80 * dp || ly > h + 80 * dp) continue;
                    boolean passed = i < nextLight;
                    drawLight(c, lights.get(i), lightColor.get(i), ly, passed, dark);
                }
            }

            // slider strip
            if (mode == SLIDER && state != READY) drawSlider(c, dark, sub, txt);

            text.setColor(txt);
            if (state == READY) { drawReady(c, w, h, dark, edgeCol, txt, sub); return; }

            if (mode == HEIGHT) {
                float ly = startY();
                btn.setColor(sub); btn.setAlpha(140);
                for (float x = 0; x < w; x += 18 * dp) c.drawRect(x, ly - 1 * dp, x + 10 * dp, ly + 1 * dp, btn);
                btn.setAlpha(255);
                text.setColor(sub); text.setTextSize(13 * dp);
                text.setTextAlign(Paint.Align.LEFT);
                c.drawText("▲ faster", 12 * dp, ly - 10 * dp, text);
                c.drawText("▼ slower", 12 * dp, ly + 22 * dp, text);
                text.setTextAlign(Paint.Align.CENTER);
            }

            // finger marker
            if (fingerX >= 0) {
                dot.setColor(state == OVER ? bad : (warning ? warnCol : txt));
                dot.setAlpha(state == OVER ? 255 : 170);
                c.drawCircle(fingerX, fingerY, 14 * dp, dot);
                dot.setAlpha(255);
            }

            // score and speed at the top
            float cx = playLeft() + playW() / 2f;
            text.setColor(txt); text.setTextSize(34 * dp); text.setFakeBoldText(true);
            c.drawText(String.valueOf(score), cx, 70 * dp, text);
            text.setFakeBoldText(false);
            if (mode != AUTO) {
                text.setTextSize(15 * dp);
                text.setColor(sub);
                String line = "Speed " + Math.round(speed);
                // tell you what the next light needs
                if (nextLight < lights.size() && yOf(lights.get(nextLight)) > -40 * dp) {
                    int col = lightColor.get(nextLight);
                    if (col == GREEN) line += "   next light: GREEN, any speed";
                    else {
                        int lim = (int) limitFor(col);
                        boolean ok = speed <= lim;
                        line += "   next light: " + (col == RED ? "RED" : "YELLOW") + ", " + lim + " or less"
                                + (ok ? "  \u2713" : "");
                        text.setColor(ok ? edgeCol : (col == RED ? 0xFFFF3B30 : (dark ? 0xFFFFD54F : 0xFFB08800)));
                    }
                }
                c.drawText(line, cx, 96 * dp, text);
            }

            if (state == PLAYING) { postInvalidateOnAnimation(); return; }
            drawGameOver(c, w, h, dark, edgeCol, txt, sub, bad);
        }

        /** A traffic light on a pole beside the road, with its own color lit: red, yellow or green.
         *  The line across the road shows its color; once you're slow enough it gets a check mark. */
        void drawLight(Canvas c, float d, int col, float y, boolean passed, boolean dark) {
            float roadCenter = roadPx(d);
            float off = (roadWidth() / 2f + 34) * dp;
            float x = roadCenter > playLeft() + playW() / 2f ? roadCenter - off : roadCenter + off;
            float bw = 26 * dp, bh = 84 * dp;
            int red = 0xFFFF3B30, yellow = 0xFFFFCC00, green = 0xFF34C759;
            int lit = col == RED ? red : col == YELLOW ? yellow : green;
            // pole
            btn.setColor(dark ? 0xFF5F6368 : 0xFF80868B);
            c.drawRect(x - 2 * dp, y, x + 2 * dp, y + 34 * dp, btn);
            // box with three lamps: red on top, yellow in the middle, green at the bottom
            btn.setColor(dark ? 0xFF2A2E35 : 0xFF3C4043);
            tmp.set(x - bw / 2, y - bh, x + bw / 2, y);
            c.drawRoundRect(tmp, 7 * dp, 7 * dp, btn);
            int dim = dark ? 0xFF3A3F47 : 0xFF5F6368;
            dot.setColor(col == RED ? red : dim);
            c.drawCircle(x, y - bh + 15 * dp, 9 * dp, dot);
            dot.setColor(col == YELLOW ? yellow : dim);
            c.drawCircle(x, y - bh / 2f, 9 * dp, dot);
            dot.setColor(col == GREEN ? green : dim);
            c.drawCircle(x, y - 15 * dp, 9 * dp, dot);
            // line across the road in the light's color
            btn.setColor((lit & 0x00FFFFFF) | (passed ? 0x66000000 : 0xCC000000));
            float half = roadWidth() / 2f * dp;
            c.drawRect(roadCenter - half, y - 2 * dp, roadCenter + half, y + 2 * dp, btn);
            // label above the light
            if (!passed && col != GREEN) {
                int lim = (int) limitFor(col);
                boolean ok = speed <= lim;
                text.setColor(ok ? green : lit); text.setTextSize(13 * dp); text.setFakeBoldText(true);
                c.drawText(ok ? "OK \u2713" : "SLOW " + lim, x, y - bh - 8 * dp, text);
                text.setFakeBoldText(false);
            }
        }

        void drawSlider(Canvas c, boolean dark, int sub, int txt) {
            sliderRect(tmp);
            btn.setColor(dark ? 0xFF22262C : 0xFFE3E6EA);
            c.drawRoundRect(tmp, 18 * dp, 18 * dp, btn);
            // middle line
            btn.setColor(sub);
            c.drawRect(tmp.left + 10 * dp, tmp.centerY() - 1 * dp, tmp.right - 10 * dp, tmp.centerY() + 1 * dp, btn);
            text.setColor(sub); text.setTextSize(13 * dp);
            c.drawText("GAS ▲", tmp.centerX(), tmp.top + 24 * dp, text);
            c.drawText("BRAKE ▼", tmp.centerX(), tmp.bottom - 14 * dp, text);
            if (sliderId != -1) {
                float ky = Math.max(tmp.top, Math.min(tmp.bottom, sliderY));
                dot.setColor(txt); dot.setAlpha(190);
                c.drawCircle(tmp.centerX(), ky, 22 * dp, dot);
                dot.setAlpha(255);
            } else {
                text.setTextSize(12 * dp);
                c.drawText("2nd", tmp.centerX(), tmp.centerY() - 10 * dp, text);
                c.drawText("finger", tmp.centerX(), tmp.centerY() + 22 * dp, text);
            }
        }

        void drawReady(Canvas c, int w, int h, boolean dark, int edgeCol, int txt, int sub) {
            float sx = roadPx(distAt(startY())), sy = startY();
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0));
            dot.setColor(edgeCol); dot.setAlpha((int) (120 + 135 * pulse));
            c.drawCircle(sx, sy, (26 + 6 * pulse) * dp, dot);
            dot.setAlpha(255);
            float top = h * 0.12f;
            text.setColor(txt); text.setTextSize(30 * dp); text.setFakeBoldText(true);
            c.drawText("Follow the Path", w / 2f, top, text);
            text.setFakeBoldText(false); text.setTextSize(17 * dp); text.setColor(sub);
            c.drawText("Put your finger on the glowing circle", w / 2f, top + 34 * dp, text);
            c.drawText("and keep it on the road. Don't lift it!", w / 2f, top + 58 * dp, text);
            c.drawText("Best: " + best, w / 2f, top + 90 * dp, text);

            float bw = Math.min(w - 40 * dp, 360 * dp), bh = 50 * dp, bt = top + 110 * dp;
            modeBtn.set((w - bw) / 2f, bt, (w + bw) / 2f, bt + bh);
            btn.setColor(dark ? 0xFF2A2E35 : 0xFFE3E6EA);
            c.drawRoundRect(modeBtn, 14 * dp, 14 * dp, btn);
            text.setColor(txt); text.setTextSize(16 * dp); text.setFakeBoldText(true);
            c.drawText("Mode: " + modeName(), w / 2f, bt + 32 * dp, text);
            text.setFakeBoldText(false); text.setColor(sub); text.setTextSize(14 * dp);
            c.drawText(modeHelp(), w / 2f, bt + bh + 22 * dp, text);
            c.drawText(hasLights() ? "Traffic lights before turns: green = any speed, yellow = medium, red = slow"
                                   : "Tap the button to change mode", w / 2f, bt + bh + 44 * dp, text);

            // slider side button (only in slider mode)
            if (mode == SLIDER) {
                float st = bt + bh + 62 * dp;
                sideBtn.set((w - bw) / 2f, st, (w + bw) / 2f, st + bh);
                btn.setColor(dark ? 0xFF2A2E35 : 0xFFE3E6EA);
                c.drawRoundRect(sideBtn, 14 * dp, 14 * dp, btn);
                text.setColor(txt); text.setTextSize(16 * dp); text.setFakeBoldText(true);
                c.drawText(sliderLeft ? "Slider: \u25C0 Left side" : "Slider: Right side \u25B6", w / 2f, st + 32 * dp, text);
                text.setFakeBoldText(false);
                // preview of where the slider will be
                sliderRect(tmp);
                btn.setColor(dark ? 0x5522262C : 0x55B0B6BE);
                c.drawRoundRect(tmp, 18 * dp, 18 * dp, btn);
            } else sideBtn.setEmpty();

            // path size and path color buttons, side by side
            float rt = bt + bh + (mode == SLIDER ? 124 : 62) * dp;
            float gap = 10 * dp, half = (bw - gap) / 2f, bl = (w - bw) / 2f;
            sizeBtn.set(bl, rt, bl + half, rt + bh);
            colorBtn.set(bl + half + gap, rt, bl + bw, rt + bh);
            btn.setColor(dark ? 0xFF2A2E35 : 0xFFE3E6EA);
            c.drawRoundRect(sizeBtn, 14 * dp, 14 * dp, btn);
            c.drawRoundRect(colorBtn, 14 * dp, 14 * dp, btn);
            text.setColor(txt); text.setTextSize(15 * dp); text.setFakeBoldText(true);
            c.drawText("Size: " + SIZE_NAMES[sizeIdx], sizeBtn.centerX(), rt + 31 * dp, text);
            // color button: a swatch of the color plus its name
            dot.setColor(edgeCol);
            c.drawCircle(colorBtn.left + 22 * dp, colorBtn.centerY(), 9 * dp, dot);
            c.drawText(COLOR_NAMES[colorIdx], colorBtn.centerX() + 10 * dp, rt + 31 * dp, text);
            text.setFakeBoldText(false);

            // speed limits for the lights (only in modes that have lights)
            if (hasLights()) {
                float lt = rt + bh + 10 * dp;
                limitBtn.set(bl, lt, bl + bw, lt + bh);
                btn.setColor(dark ? 0xFF2A2E35 : 0xFFE3E6EA);
                c.drawRoundRect(limitBtn, 14 * dp, 14 * dp, btn);
                float[] L = LIMITS[limitIdx];
                text.setColor(txt); text.setTextSize(15 * dp); text.setFakeBoldText(true);
                c.drawText("Light limits: " + LIMIT_NAMES[limitIdx], w / 2f, lt + 22 * dp, text);
                text.setFakeBoldText(false); text.setColor(sub); text.setTextSize(13 * dp);
                c.drawText("Yellow " + (int) L[0] + " or less  \u2022  Red " + (int) L[1] + " or less", w / 2f, lt + 40 * dp, text);
            } else limitBtn.setEmpty();
            postInvalidateOnAnimation();
        }

        void drawGameOver(Canvas c, int w, int h, boolean dark, int edgeCol, int txt, int sub, int bad) {
            panel.setColor(dark ? 0xEE1E2126 : 0xEEFFFFFF);
            float pw = Math.min(w - 40 * dp, 380 * dp), ph = 250 * dp;
            float left = (w - pw) / 2f, top = h * 0.28f;
            c.drawRoundRect(left, top, left + pw, top + ph, 20 * dp, 20 * dp, panel);
            text.setColor(bad); text.setTextSize(28 * dp); text.setFakeBoldText(true);
            c.drawText("Game over", w / 2f, top + 50 * dp, text);
            text.setFakeBoldText(false); text.setTextSize(17 * dp);
            c.drawText(overReason, w / 2f, top + 82 * dp, text);
            text.setColor(txt); text.setTextSize(22 * dp);
            c.drawText("Score: " + score, w / 2f, top + 125 * dp, text);
            text.setColor(newBest ? edgeCol : sub); text.setTextSize(18 * dp);
            c.drawText(newBest ? "New best score!" : "Best: " + best, w / 2f, top + 160 * dp, text);
            text.setColor(sub); text.setTextSize(16 * dp);
            c.drawText("Tap anywhere to try again", w / 2f, top + 210 * dp, text);
        }
    }
}
