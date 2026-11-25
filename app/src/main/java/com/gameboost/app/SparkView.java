package com.gameboost.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Grafik garis mini (sparkline) dengan isian lembut di bawah garis. Menyimpan 30 titik terakhir. */
public class SparkView extends View {

    private static final int N = 30;

    private final float[] data = new float[N];
    private int count = 0;
    private float fixedMax = 100f;      // 0 = skala otomatis
    private float autoFloor = 1f;       // skala otomatis tidak boleh lebih kecil dari ini

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();

    public SparkView(Context c) {
        this(c, null);
    }

    public SparkView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.8f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(0.5f * d);
        base.setColor(0x33FFFFFF);
        setColor(0xFF0A84FF);
    }

    public void setColor(int color) {
        line.setColor(color);
        fill.setColor((color & 0x00FFFFFF) | 0x33000000);
        invalidate();
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
        invalidate();
    }

    public void clear() {
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float pad = 2f * getResources().getDisplayMetrics().density;
        canvas.drawLine(0, h - pad, w, h - pad, base);
        if (count < 2) return;

        float max = fixedMax;
        if (max <= 0f) {
            max = autoFloor;
            for (int i = 0; i < count; i++) max = Math.max(max, data[i]);
        }
        float usableH = h - 2f * pad;
        float step = (w - 2f * pad) / (N - 1);
        int offset = N - count;               // titik terbaru menempel di sisi kanan

        path.reset();
        area.reset();
        float firstX = 0f;
        float lastX = 0f;
        for (int i = 0; i < count; i++) {
            float x = pad + (offset + i) * step;
            float v = Math.max(0f, Math.min(data[i], max));
            float y = h - pad - (v / max) * usableH;
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, y);
                firstX = x;
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
        }
        area.lineTo(lastX, h - pad);
        area.lineTo(firstX, h - pad);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }
}
