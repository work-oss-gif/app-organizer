package com.example.followpath;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
        int state = READY;

        final float dp = getResources().getDisplayMetrics().density;
        final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path shape = new Path();

        float scroll = 0;          // how far the road has moved (in dp)
        float speed;               // dp per second
        long lastTime;
        float fingerX = -1, fingerY = -1;
        int pointerId = -1;
        int score = 0, best;
        boolean newBest = false;
        long overAt = 0;
        long lastWarn = 0;          // when the last warning buzz happened
        boolean warning = false;    // finger is close to the edge right now
        final SharedPreferences prefs = getSharedPreferences("game", Context.MODE_PRIVATE);

        GameView(Context c) {
            super(c);
            best = prefs.getInt("best", 0);
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

        // ---------- the pattern (always the same) ----------

        /** Center of the road at distance d (dp), as a fraction of screen width 0..1. Same every game. */
        float roadX(float d) {
            double x = 0.5
                    + 0.22 * Math.sin(d / 260.0)
                    + 0.12 * Math.sin(d / 113.0 + 1.3)
                    + 0.05 * Math.sin(d / 47.0 + 2.1);
            return (float) Math.max(0.12, Math.min(0.88, x));
        }

        /** Road width in dp: starts wide, slowly gets narrower. */
        float roadWidth() { return Math.max(46f, 84f - scroll / 250f); }

        /** Distance along the road that sits at screen row y. Things higher up are "coming next". */
        float distAt(float y) { return scroll + (getHeight() - y) / dp; }

        float startY() { return getHeight() * 0.72f; }

        // ---------- game loop ----------

        void start() {
            state = PLAYING;
            scroll = 0;
            speed = 110;
            score = 0;
            newBest = false;
            warning = false;
            lastTime = System.nanoTime();
            postInvalidateOnAnimation();
        }

        void gameOver() {
            if (state != PLAYING) return;
            state = OVER;
            overAt = System.currentTimeMillis();
            if (score > best) { best = score; newBest = true; prefs.edit().putInt("best", best).apply(); }
            buzz();
            invalidate();
        }

        void pauseToGameOver() { if (state == PLAYING) gameOver(); }

        void buzz() {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(250);
        }

        /** How far the finger is from the middle of the road: 0 = center, 1 = right at the edge, above 1 = off. */
        float edgeRatio(float x, float y) {
            float cx = roadX(distAt(y)) * getWidth();
            return Math.abs(x - cx) / ((roadWidth() / 2f + 6f) * dp);
        }

        /** Is the finger on the road right now? A little extra room (6dp) so it feels fair. */
        boolean onRoad(float x, float y) { return edgeRatio(x, y) <= 1f; }

        /** Short tick buzz used as a warning when the finger gets near the edge. */
        void warnBuzz() {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(35);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (state == READY) {
                if (action == MotionEvent.ACTION_DOWN && onRoad(e.getX(), e.getY())
                        && Math.abs(e.getY() - startY()) < 70 * dp) {
                    pointerId = e.getPointerId(0);
                    fingerX = e.getX(); fingerY = e.getY();
                    start();
                }
                return true;
            }
            if (state == OVER) {
                // Wait half a second so the losing touch doesn't instantly restart.
                if (action == MotionEvent.ACTION_DOWN && System.currentTimeMillis() - overAt > 500) {
                    state = READY; scroll = 0; invalidate();
                }
                return true;
            }
            // PLAYING
            int idx = e.findPointerIndex(pointerId);
            if (action == MotionEvent.ACTION_CANCEL) { gameOver(); return true; }
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && e.getPointerId(e.getActionIndex()) == pointerId) { gameOver(); return true; }
            if (idx >= 0) { fingerX = e.getX(idx); fingerY = e.getY(idx); }
            return true;
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
            int w = getWidth(), h = getHeight();

            // move the road forward
            if (state == PLAYING) {
                long now = System.nanoTime();
                float dt = Math.min(0.05f, (now - lastTime) / 1e9f);
                lastTime = now;
                scroll += speed * dt;
                speed = Math.min(480, speed + 6 * dt);   // slowly speeds up
                score = (int) (scroll / 10);
                float r = edgeRatio(fingerX, fingerY);
                if (r > 1f) gameOver();
                else {
                    // Close to the edge (outer 30% of the road): buzz about 6 times a second.
                    warning = r > 0.7f;
                    long ms = System.currentTimeMillis();
                    if (warning && ms - lastWarn > 160) { lastWarn = ms; warnBuzz(); }
                }
            }

            c.drawColor(bg);

            // build the road shape from top to bottom of the screen
            shape.reset();
            float step = 6 * dp;
            for (float y = -step; y <= h + step; y += step) {
                float x = roadX(distAt(y)) * w;
                if (y < 0) shape.moveTo(x, y); else shape.lineTo(x, y);
            }
            float rw = roadWidth() * dp;
            edge.setColor(edgeCol); edge.setStrokeWidth(rw + 6 * dp);
            road.setColor(roadCol); road.setStrokeWidth(rw);
            mid.setColor(midCol);
            c.drawPath(shape, edge);
            c.drawPath(shape, road);
            c.drawPath(shape, mid);

            text.setColor(txt);
            if (state == READY) {
                float sx = roadX(distAt(startY())) * w, sy = startY();
                float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0));
                dot.setColor(edgeCol); dot.setAlpha((int) (120 + 135 * pulse));
                c.drawCircle(sx, sy, (26 + 6 * pulse) * dp, dot);
                dot.setAlpha(255);
                text.setTextSize(30 * dp); text.setFakeBoldText(true);
                c.drawText("Follow the Path", w / 2f, h * 0.16f, text);
                text.setFakeBoldText(false); text.setTextSize(17 * dp);
                text.setColor(sub);
                c.drawText("Put your finger on the glowing circle", w / 2f, h * 0.16f + 34 * dp, text);
                c.drawText("and keep it on the road. Don't lift it!", w / 2f, h * 0.16f + 58 * dp, text);
                c.drawText("Best: " + best, w / 2f, h * 0.16f + 92 * dp, text);
                postInvalidateOnAnimation();   // keep the circle pulsing
                return;
            }

            // finger marker
            if (fingerX >= 0) {
                int warnCol = dark ? 0xFFFFB74D : 0xFFE37400;   // orange = too close to the edge
                dot.setColor(state == OVER ? bad : (warning ? warnCol : txt));
                dot.setAlpha(state == OVER ? 255 : 170);
                c.drawCircle(fingerX, fingerY, 14 * dp, dot);
                dot.setAlpha(255);
            }

            // score at the top
            text.setColor(txt); text.setTextSize(34 * dp); text.setFakeBoldText(true);
            c.drawText(String.valueOf(score), w / 2f, 70 * dp, text);
            text.setFakeBoldText(false);

            if (state == PLAYING) { postInvalidateOnAnimation(); return; }

            // game over panel
            panel.setColor(dark ? 0xEE1E2126 : 0xEEFFFFFF);
            float pw = Math.min(w - 40 * dp, 360 * dp), ph = 220 * dp;
            float left = (w - pw) / 2f, top = h * 0.30f;
            c.drawRoundRect(left, top, left + pw, top + ph, 20 * dp, 20 * dp, panel);
            text.setColor(bad); text.setTextSize(28 * dp); text.setFakeBoldText(true);
            c.drawText("Game over", w / 2f, top + 50 * dp, text);
            text.setFakeBoldText(false);
            text.setColor(txt); text.setTextSize(22 * dp);
            c.drawText("Score: " + score, w / 2f, top + 95 * dp, text);
            text.setColor(newBest ? edgeCol : sub); text.setTextSize(18 * dp);
            c.drawText(newBest ? "New best score!" : "Best: " + best, w / 2f, top + 130 * dp, text);
            text.setColor(sub); text.setTextSize(16 * dp);
            c.drawText("Tap anywhere to try again", w / 2f, top + 180 * dp, text);
        }
    }
}
