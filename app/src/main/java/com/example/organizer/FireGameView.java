package com.example.organizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

// A burning house. Touch and drag anywhere: water sprays from the hose at the
// bottom towards your finger. Put out every fire before the house burns down.
public class FireGameView extends View {

    static class Fire { float x, y, r; }
    static class Drop { float x, y, vx, vy, life; }

    private enum State { PLAYING, WON, LOST }

    private final Random random = new Random();
    private final ArrayList<Fire> fires = new ArrayList<>();
    private final ArrayList<Drop> drops = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private State state = State.PLAYING;
    private int level = 1;
    private int spawned;          // fires started so far this level
    private int totalFires;       // fires that will start this level
    private float spawnTimer;     // seconds until the next fire starts
    private float houseHealth = 100f;
    private boolean spraying;
    private float touchX, touchY;
    private long lastTime;
    private float clock;          // seconds, used for flickering
    private float u;              // 1 "unit" = 1/360 of the screen width
    private boolean started;

    public FireGameView(Context context) {
        super(context);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        u = w / 360f;
        if (!started) {
            started = true;
            startLevel();
        }
    }

    private void startLevel() {
        fires.clear();
        drops.clear();
        spawned = 0;
        totalFires = 4 + level * 2;
        spawnTimer = 0.5f;
        houseHealth = 100f;
        state = State.PLAYING;
        lastTime = 0;
    }

    private float spawnInterval() {
        return Math.max(1.0f, 3.0f - 0.25f * level);
    }

    private void spawnFire() {
        int w = getWidth(), h = getHeight();
        Fire f = new Fire();
        f.y = h * (0.30f + random.nextFloat() * 0.45f);
        // The roof is narrow at the top, so keep fires inside the house shape.
        float half = f.y < h * 0.40f ? 0.12f : 0.28f;
        f.x = w * (0.5f - half + random.nextFloat() * half * 2);
        f.r = 14 * u;
        fires.add(f);
        spawned++;
    }

    // ---------- game logic ----------

    private void update(float dt) {
        clock += dt;

        // New fires start over time.
        spawnTimer -= dt;
        if (spawnTimer <= 0 && spawned < totalFires) {
            spawnFire();
            spawnTimer = spawnInterval();
        }

        // Fires grow, and burn the house.
        float fireSize = 0;
        for (Fire f : fires) {
            f.r = Math.min(f.r + (3f + level) * u * dt, 50 * u);
            fireSize += f.r / u;
        }
        houseHealth -= fireSize * 0.012f * dt;

        // Water leaves the hose while the finger is down.
        if (spraying) {
            float nx = getWidth() / 2f, ny = getHeight() * 0.95f;
            float dx = touchX - nx, dy = touchY - ny;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > 1) {
                float speed = 700 * u;
                for (int i = 0; i < 3; i++) {
                    Drop d = new Drop();
                    d.x = nx;
                    d.y = ny;
                    // A little random wobble so the spray is a cone, not a laser.
                    float wobble = (random.nextFloat() - 0.5f) * 0.18f;
                    float cos = (float) Math.cos(wobble), sin = (float) Math.sin(wobble);
                    float ux = dx / dist, uy = dy / dist;
                    d.vx = (ux * cos - uy * sin) * speed;
                    d.vy = (ux * sin + uy * cos) * speed;
                    d.life = dist / speed + 0.05f;
                    drops.add(d);
                }
            }
        }

        // Move the water, and let it hit fires.
        Iterator<Drop> it = drops.iterator();
        while (it.hasNext()) {
            Drop d = it.next();
            d.x += d.vx * dt;
            d.y += d.vy * dt;
            d.life -= dt;
            boolean dead = d.life <= 0;
            if (!dead) {
                for (Fire f : fires) {
                    float dx = d.x - f.x, dy = d.y - f.y;
                    if (dx * dx + dy * dy < f.r * f.r) {
                        f.r -= 0.3f * u;
                        dead = true;
                        break;
                    }
                }
            }
            if (dead) it.remove();
        }

        // A fire that shrinks small enough is out.
        Iterator<Fire> fi = fires.iterator();
        while (fi.hasNext()) {
            if (fi.next().r < 5 * u) fi.remove();
        }

        if (houseHealth <= 0) {
            houseHealth = 0;
            state = State.LOST;
            spraying = false;
        } else if (spawned >= totalFires && fires.isEmpty()) {
            state = State.WON;
            spraying = false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (state != State.PLAYING) {
            if (action == MotionEvent.ACTION_UP) {
                if (state == State.WON) level++; else level = 1;
                startLevel();
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            spraying = true;
            touchX = e.getX();
            touchY = e.getY();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            spraying = false;
        }
        return true;
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        if (state == State.PLAYING) {
            float dt = lastTime == 0 ? 0 : (now - lastTime) / 1e9f;
            update(Math.min(dt, 0.05f)); // clamp so a lag spike doesn't jump the game
        }
        lastTime = now;

        drawScene(c);
        drawHud(c);
        if (state != State.PLAYING) drawOverlay(c);

        if (state == State.PLAYING) postInvalidateOnAnimation();
    }

    private void drawScene(Canvas c) {
        int w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);

        // Night sky and ground.
        c.drawColor(0xFF1B2440);
        paint.setColor(0xFF2E4A2E);
        c.drawRect(0, h * 0.80f, w, h, paint);

        // House walls, getting darker as it burns.
        int shade = (int) (0x90 * (houseHealth / 100f)) + 0x30;
        paint.setColor(0xFF000000 | (shade << 16) | ((shade * 3 / 4) << 8) | (shade / 2));
        c.drawRect(w * 0.15f, h * 0.40f, w * 0.85f, h * 0.80f, paint);

        // Roof.
        paint.setColor(0xFF5A2A2A);
        path.reset();
        path.moveTo(w * 0.10f, h * 0.40f);
        path.lineTo(w * 0.50f, h * 0.22f);
        path.lineTo(w * 0.90f, h * 0.40f);
        path.close();
        c.drawPath(path, paint);

        // Windows (glowing yellow) and door.
        paint.setColor(0xFFFFD27A);
        c.drawRect(w * 0.22f, h * 0.46f, w * 0.38f, h * 0.55f, paint);
        c.drawRect(w * 0.62f, h * 0.46f, w * 0.78f, h * 0.55f, paint);
        c.drawRect(w * 0.22f, h * 0.60f, w * 0.38f, h * 0.69f, paint);
        c.drawRect(w * 0.62f, h * 0.60f, w * 0.78f, h * 0.69f, paint);
        paint.setColor(0xFF3A2414);
        c.drawRect(w * 0.44f, h * 0.64f, w * 0.56f, h * 0.80f, paint);

        // Fires.
        for (Fire f : fires) drawFlame(c, f);

        // Hose and water.
        float nx = w / 2f, ny = h * 0.95f;
        paint.setColor(0xFF444444);
        c.drawRect(nx - 8 * u, ny - 4 * u, nx + 8 * u, h, paint);
        paint.setColor(0xFF6EC6FF);
        for (Drop d : drops) c.drawCircle(d.x, d.y, 3.5f * u, paint);
    }

    private void drawFlame(Canvas c, Fire f) {
        float flicker = 1f + 0.12f * (float) Math.sin(clock * 14 + f.x);
        float r = f.r * flicker;
        paint.setColor(0xFFFF4A12);
        flamePath(f.x, f.y, r);
        c.drawPath(path, paint);
        paint.setColor(0xFFFFB020);
        flamePath(f.x, f.y + r * 0.1f, r * 0.7f);
        c.drawPath(path, paint);
        paint.setColor(0xFFFFF0A0);
        flamePath(f.x, f.y + r * 0.2f, r * 0.4f);
        c.drawPath(path, paint);
    }

    // A teardrop shape: pointy at the top, round at the bottom.
    private void flamePath(float x, float y, float r) {
        path.reset();
        path.moveTo(x, y - 1.6f * r);
        path.cubicTo(x + 1.2f * r, y - 0.4f * r, x + 0.9f * r, y + r, x, y + r);
        path.cubicTo(x - 0.9f * r, y + r, x - 1.2f * r, y - 0.4f * r, x, y - 1.6f * r);
        path.close();
    }

    private void drawHud(Canvas c) {
        int w = getWidth();
        paint.setTextSize(18 * u);
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("Level " + level, 12 * u, 36 * u, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        int left = (totalFires - spawned) + fires.size();
        c.drawText("Fires left: " + left, w - 12 * u, 36 * u, paint);

        // House health bar.
        float bx = 12 * u, by = 48 * u, bw = w - 24 * u, bh = 12 * u;
        paint.setColor(0xFF333333);
        c.drawRect(bx, by, bx + bw, by + bh, paint);
        paint.setColor(houseHealth > 30 ? 0xFF4CD964 : 0xFFFF3B30);
        c.drawRect(bx, by, bx + bw * houseHealth / 100f, by + bh, paint);

        if (state == State.PLAYING && level == 1 && spawned <= 2) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16 * u);
            paint.setColor(0xFFFFFFFF);
            c.drawText("Touch and drag to spray water!", w / 2f, getHeight() * 0.88f, paint);
        }
    }

    private void drawOverlay(Canvas c) {
        int w = getWidth(), h = getHeight();
        paint.setColor(0xAA000000);
        c.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(34 * u);
        c.drawText(state == State.WON ? "House saved!" : "House burned down", w / 2f, h * 0.45f, paint);
        paint.setTextSize(18 * u);
        c.drawText(state == State.WON ? "Tap for level " + (level + 1) : "Tap to try again", w / 2f, h * 0.52f, paint);
    }
}
