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
 * In the two control modes, red stop lights before sharp turns: be slow when you pass them.
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
        static final float SAFE = 50f;      // speed you must be at or below when passing a red light
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
        float lightsScannedTo = 300;
        int nextLight = 0;          // index of the next light the finger has not passed yet

        final SharedPreferences prefs = getSharedPreferences("game", Context.MODE_PRIVATE);

        GameView(Context c) {
            super(c);
            mode = prefs.getInt("mode", prefs.getBoolean("fingerSpeed", false) ? HEIGHT : AUTO);
            best = prefs.getInt(bestKey(), 0);
            sliderLeft = prefs.getBoolean("sliderLeft", false);
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

        float roadWidth() { return Math.max(46f, 84f - scroll / 250f); }
        float distAt(float y) { return scroll + (getHeight() - y) / dp; }
        float yOf(float d) { return getHeight() - (d - scroll) * dp; }
        float startY() { return getHeight() * 0.72f; }

        /** Find the sharp turns ahead and put a stop light just before each one. */
        void scanLights(float upTo) {
            float d = lightsScannedTo;
            float last = lights.isEmpty() ? -1e9f : lights.get(lights.size() - 1) + 120;
            while (d < upTo) {
                float x0 = roadX(d), sharp = 0;
                for (int i = 1; i <= 12; i++) sharp = Math.max(sharp, Math.abs(roadX(d + i * 10) - x0));
                if (sharp > 0.2f && d - last > 700) { lights.add(d - 120); last = d; }
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
                    if (speed > SAFE) { gameOver("You ran a red light!"); return; }
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
            int roadCol = dark ? 0xFF2E7D5B : 0xFF7FD3A6;
            int edgeCol = dark ? 0xFF4CC38A : 0xFF2E9E6A;
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
                    drawLight(c, lights.get(i), ly, passed, dark, txt);
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
                boolean tooFast = speed > SAFE;
                text.setTextSize(15 * dp);
                text.setColor(tooFast ? sub : edgeCol);
                c.drawText("Speed " + Math.round(speed) + (tooFast ? "" : "  (safe for red lights)"), cx, 96 * dp, text);
            }

            if (state == PLAYING) { postInvalidateOnAnimation(); return; }
            drawGameOver(c, w, h, dark, edgeCol, txt, sub, bad);
        }

        /** A traffic light on a pole beside the road. Red while you're too fast, green once you're slow enough. */
        void drawLight(Canvas c, float d, float y, boolean passed, boolean dark, int txt) {
            float roadCenter = roadPx(d);
            float off = (roadWidth() / 2f + 34) * dp;
            // put it on the side of the road with more room
            float x = roadCenter > playLeft() + playW() / 2f ? roadCenter - off : roadCenter + off;
            boolean safe = speed <= SAFE;
            float bw = 26 * dp, bh = 62 * dp;
            // pole
            btn.setColor(dark ? 0xFF5F6368 : 0xFF80868B);
            c.drawRect(x - 2 * dp, y, x + 2 * dp, y + 34 * dp, btn);
            // box
            btn.setColor(dark ? 0xFF2A2E35 : 0xFF3C4043);
            tmp.set(x - bw / 2, y - bh, x + bw / 2, y);
            c.drawRoundRect(tmp, 7 * dp, 7 * dp, btn);
            int off2 = dark ? 0xFF3A3F47 : 0xFF5F6368;
            int red = 0xFFFF3B30, green = 0xFF34C759;
            boolean showRed = !passed && !safe;
            boolean showGreen = passed || safe;
            dot.setColor(showRed ? red : off2);
            c.drawCircle(x, y - bh + 16 * dp, 9 * dp, dot);
            dot.setColor(showGreen ? green : off2);
            c.drawCircle(x, y - 16 * dp, 9 * dp, dot);
            // stop line across the road
            btn.setColor(showRed ? 0xCCFF3B30 : 0x8834C759);
            float half = roadWidth() / 2f * dp;
            c.drawRect(roadCenter - half, y - 2 * dp, roadCenter + half, y + 2 * dp, btn);
            if (showRed) {
                text.setColor(red); text.setTextSize(13 * dp); text.setFakeBoldText(true);
                c.drawText("SLOW", x, y - bh - 8 * dp, text);
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
            c.drawText(hasLights() ? "Red lights before sharp turns: be slow (" + (int) SAFE + " or less) when you pass them"
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
