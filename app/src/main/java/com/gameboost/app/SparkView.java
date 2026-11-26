package com.gameboost.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Grafik garis hidup: kurva mulus yang bergeser halus tiap data baru, isian gradasi,
 * garis berpendar yang memudar di sisi kiri, dan titik berdenyut di ujung terbaru.
 */
public class SparkView extends View {

    private static final int N = 30;
    private static final long SLIDE_MS = 1300;
    private static final long FRAME_MS = 33;      // ~30 fps, hemat tenaga saat main

    private final float[] data = new float[N];
    private int count = 0;
    private long pushedAt = 0;
    private float fixedMax = 100f;      // 0 = skala otomatis
    private float autoFloor = 1f;
    private float shownMax = 0f;        // skala yang ditampilkan, bergerak halus
    private int color = 0xFF0A84FF;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private final float d;

    public SparkView(Context c) {
        this(c, null);
    }

    public SparkView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{line, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        line.setStrokeWidth(2f * d);
        glow.setStrokeWidth(6f * d);
        fill.setStyle(Paint.Style.FILL);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(0.5f * d);
        grid.setColor(0x24FFFFFF);
        dot.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    public void setColor(int c) {
        color = c;
        rebuildShaders();
        invalidate();
    }

    private void rebuildShaders() {
        int w = getWidth(), h = getHeight();
        int rgb = color & 0x00FFFFFF;
        if (w <= 0 || h <= 0) return;
        fill.setShader(new LinearGradient(0, 0, 0, h, (0x66 << 24) | rgb, rgb, Shader.TileMode.CLAMP));
        // Garis dan pendar memudar di kiri: data lama terasa menghilang.
        line.setShader(new LinearGradient(0, 0, w, 0, new int[]{rgb, color, color},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        glow.setShader(new LinearGradient(0, 0, w, 0, new int[]{rgb, (0x40 << 24) | rgb, (0x40 << 24) | rgb},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rebuildShaders();
    }

    /** Skala tetap (mis. 100 untuk persen). Beri 0 untuk skala otomatis. */
    public void setFixedMax(float max, float autoFloor) {
        this.fixedMax = max;
        this.autoFloor = Math.max(0.0001f, autoFloor);
        invalidate();
    }

    public void push(float v) {
        if (count < N) {
            data[count++] = v;
        } else {
            System.arraycopy(data, 1, data, 0, N - 1);
            data[N - 1] = v;
        }
        pushedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    public void clear() {
        count = 0;
        shownMax = 0f;
        invalidate();
    }

    private float targetMax() {
        if (fixedMax > 0f) return fixedMax;
        float m = autoFloor;
        for (int i = 0; i < count; i++) m = Math.max(m, data[i]);
        return m * 1.15f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float pad = 3f * d;
        canvas.drawLine(0, h - pad, w, h - pad, grid);
        canvas.drawLine(0, pad + (h - 2 * pad) * 0.5f, w, pad + (h - 2 * pad) * 0.5f, grid);
        if (count < 2) return;

        long now = SystemClock.uptimeMillis();
        float t = Math.min(1f, (now - pushedAt) / (float) SLIDE_MS);
        t = 1f - (1f - t) * (1f - t);                       // melambat di akhir

        float target = targetMax();
        shownMax = shownMax <= 0f ? target : shownMax + (target - shownMax) * 0.12f;
        float max = Math.max(shownMax, 0.0001f);

        float usableH = h - 2f * pad;
        float step = (w - 2f * pad) / (N - 1);
        int offset = N - count;
        float lastX = w - pad;

        float px = 0, py = 0, firstX = 0;
        path.reset();
        area.reset();
        float endX = 0, endY = 0;
        for (int i = 0; i < count; i++) {
            float x;
            float v = Math.max(0f, Math.min(data[i], max));
            if (i == count - 1) {
                x = lastX;
                float prev = Math.max(0f, Math.min(data[i - 1], max));
                v = prev + (v - prev) * t;                // titik baru "tumbuh" dari titik lama
            } else {
                x = pad + (offset + i) * step + (1f - t) * step;   // seluruh grafik bergeser ke kiri
            }
            float y = h - pad - (v / max) * usableH;
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, y);
                firstX = x;
            } else {
                float mx = (px + x) / 2f;
                path.cubicTo(mx, py, mx, y, x, y);
                area.cubicTo(mx, py, mx, y, x, y);
            }
            px = x;
            py = y;
            endX = x;
            endY = y;
        }
        area.lineTo(endX, h - pad);
        area.lineTo(firstX, h - pad);
        area.close();

        canvas.drawPath(area, fill);
        canvas.drawPath(path, glow);
        canvas.drawPath(path, line);

        // Titik ujung berdenyut
        float pulse = (float) (Math.sin(now / 350.0) * 0.5 + 0.5);
        int rgb = color & 0x00FFFFFF;
        dot.setColor((((int) (0x55 * (1f - pulse))) << 24) | rgb);
        canvas.drawCircle(endX, endY, (3f + 5f * pulse) * d, dot);
        dot.setColor(color);
        canvas.drawCircle(endX, endY, 2.6f * d, dot);
        dot.setColor(0xFFFFFFFF);
        canvas.drawCircle(endX, endY, 1.1f * d, dot);

        postInvalidateDelayed(FRAME_MS);
    }
}
