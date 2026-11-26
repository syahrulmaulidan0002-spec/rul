package com.gameboost.app;

import android.content.Context;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Grafik "gunung titik": riwayat data asli digambar sebagai punggung gunung dari titik-titik.
 * Lapisan depan = data terbaru, lapisan di belakang = data beberapa saat sebelumnya (digeser naik,
 * lebih redup) supaya terasa dalam. Titik terang di ujung kanan = nilai sekarang, lingkaran tipis = puncak.
 */
public class SparkView extends View {

    private static final int N = 90;              // riwayat ~3 menit (sampel tiap 2 dtk)
    private static final int WIN = 30;            // jumlah titik yang tampak per lapisan
    private static final int LAG = 8;             // jarak waktu antar lapisan (sampel)
    private static final long SLIDE_MS = 1300;
    private static final long FRAME_MS = 33;      // ~30 fps, hanya saat panel terbuka

    private final float[] data = new float[N];
    private int count = 0;
    private long pushedAt = 0;
    private float fixedMax = 100f;      // 0 = skala otomatis
    private float autoFloor = 1f;
    private float shownMax = 0f;
    private int color = 0xFF0A84FF;
    private final int layers;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mask = new Paint();
    private final Path path = new Path();
    private final Path area = new Path();
    private final float[] ptX = new float[WIN];
    private final float[] ptY = new float[WIN];
    private final float d;
    private float peakX, peakY;

    public SparkView(Context c) {
        this(c, null);
    }

    public SparkView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        boolean weak = false;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            weak = am != null && am.isLowRamDevice();
        } catch (Exception ignored) {
        }
        layers = weak ? 3 : 6;
        for (Paint p : new Paint[]{line, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        line.setStrokeWidth(1.2f * d);
        line.setColor(0xFFFFFFFF);
        glow.setStrokeWidth(3.4f * d);
        fill.setStyle(Paint.Style.FILL);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(0.5f * d);
        base.setColor(0x40FFFFFF);
        dot.setStyle(Paint.Style.FILL);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setColor(color);
    }

    public void setColor(int c) {
        color = c;
        // Pola titik: warna jenis dicampur putih supaya terang di atas kaca.
        int r = ((c >> 16) & 0xFF) + (255 - ((c >> 16) & 0xFF)) * 6 / 10;
        int g = ((c >> 8) & 0xFF) + (255 - ((c >> 8) & 0xFF)) * 6 / 10;
        int b = (c & 0xFF) + (255 - (c & 0xFF)) * 6 / 10;
        int size = Math.max(4, Math.round(3.4f * d));
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000 | (r << 16) | (g << 8) | b);
        bc.drawCircle(size / 2f, size / 2f, 0.85f * d, p);
        dots.setShader(new BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        rebuildShaders();
        invalidate();
    }

    private void rebuildShaders() {
        int w = getWidth(), h = getHeight();
        int rgb = color & 0x00FFFFFF;
        if (w <= 0 || h <= 0) return;
        fill.setShader(new LinearGradient(0, 0, 0, h, (0x57 << 24) | rgb, rgb, Shader.TileMode.CLAMP));
        mask.setShader(new LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP));
        glow.setColor((0xB0 << 24) | rgb);
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

    /** Membangun kurva satu lapisan. Mengembalikan false bila datanya belum cukup. */
    private boolean build(int k, float t, float w, float h, float max) {
        int end = count - 1 - k * LAG;
        if (end < 1) return false;
        float pad = 2f * d;
        float amp = 1f - k * 0.06f;
        float yo = k * h * 0.07f;
        float usable = (h - pad) * 0.84f - yo;
        float step = (w - pad) / (WIN - 1);
        float lastX = w - pad;
        for (int i = 0; i < WIN; i++) {
            int idx = Math.max(0, end - (WIN - 1) + i);
            float v = Math.max(0f, Math.min(data[idx], max));
            float x;
            if (i == WIN - 1) {
                x = lastX;
                float prev = Math.max(0f, Math.min(data[Math.max(0, idx - 1)], max));
                v = prev + (v - prev) * t;
            } else {
                x = pad / 2f + i * step + (1f - t) * step;
            }
            ptX[i] = x;
            ptY[i] = h - pad - yo - (v / max) * amp * usable;
        }
        path.reset();
        area.reset();
        path.moveTo(ptX[0], ptY[0]);
        area.moveTo(ptX[0], ptY[0]);
        for (int i = 1; i < WIN; i++) {
            float mx = (ptX[i - 1] + ptX[i]) / 2f;
            path.cubicTo(mx, ptY[i - 1], mx, ptY[i], ptX[i], ptY[i]);
            area.cubicTo(mx, ptY[i - 1], mx, ptY[i], ptX[i], ptY[i]);
        }
        area.lineTo(ptX[WIN - 1], h - pad);
        area.lineTo(ptX[0], h - pad);
        area.close();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        canvas.drawLine(0, h - 2f * d, w, h - 2f * d, base);
        if (count < 2) return;

        long now = SystemClock.uptimeMillis();
        float t = Math.min(1f, (now - pushedAt) / (float) SLIDE_MS);
        t = 1f - (1f - t) * (1f - t);

        float target = targetMax();
        shownMax = shownMax <= 0f ? target : shownMax + (target - shownMax) * 0.12f;
        float max = Math.max(shownMax, 0.0001f);

        // Lapisan belakang: titik-titik yang makin redup ke bawah.
        int sc = canvas.saveLayer(0, 0, w, h, null);
        for (int k = layers - 1; k >= 1; k--) {
            if (!build(k, t, w, h, max)) continue;
            dots.setAlpha((int) (255 * Math.max(0.12f, 0.46f - k * 0.06f)));
            canvas.drawPath(area, dots);
        }
        if (build(0, t, w, h, max)) {
            dots.setAlpha(255);
            canvas.drawPath(area, dots);
        }
        canvas.drawRect(0, 0, w, h, mask);
        canvas.restoreToCount(sc);

        // Lapisan depan: isian gradasi, pendar, garis puncak.
        if (!build(0, t, w, h, max)) return;
        float endX = ptX[WIN - 1], endY = ptY[WIN - 1];
        peakX = ptX[0];
        peakY = ptY[0];
        for (int i = 1; i < WIN; i++) {
            if (ptY[i] < peakY) {
                peakY = ptY[i];
                peakX = ptX[i];
            }
        }
        canvas.drawPath(area, fill);
        canvas.drawPath(path, glow);
        canvas.drawPath(path, line);

        // Lingkaran tipis di puncak tertinggi (kalau bukan di ujung)
        if (Math.abs(peakX - endX) > 8f * d) {
            dot.setStyle(Paint.Style.STROKE);
            dot.setStrokeWidth(0.8f * d);
            dot.setColor(0x80FFFFFF);
            canvas.drawCircle(peakX, peakY, 3.6f * d, dot);
            dot.setStyle(Paint.Style.FILL);
        }

        // Titik terang berdenyut di data terbaru
        float pulse = (float) (Math.sin(now / 350.0) * 0.5 + 0.5);
        int rgb = color & 0x00FFFFFF;
        dot.setColor((((int) (0x55 * (1f - pulse))) << 24) | rgb);
        canvas.drawCircle(endX, endY, (3f + 4f * pulse) * d, dot);
        dot.setColor(0xFFFFFFFF);
        canvas.drawCircle(endX, endY, 2.2f * d, dot);

        postInvalidateDelayed(FRAME_MS);
    }
}
