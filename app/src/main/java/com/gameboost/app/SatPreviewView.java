package com.gameboost.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Simulasi warna game yang terus bergerak: kiri = warna asli, kanan = setelah dipertajam.
 * Adegan (langit, matahari, bukit, bola warna) bergerak tanpa henti supaya perbedaan terlihat jelas.
 */
public class SatPreviewView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hill = new Path();
    private final float d;
    private float sat = 1f;
    private final int[] orb = {0xFFE0605A, 0xFFE8B04A, 0xFF6FB65C, 0xFF4F96CC, 0xFF9A6FC8};

    public SatPreviewView(Context c) {
        this(c, null);
    }

    public SatPreviewView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        label.setTextSize(11f * d);
        label.setColor(0xFFFFFFFF);
        label.setShadowLayer(3f * d, 0, 0, 0xCC000000);
        divider.setColor(0xFFFFFFFF);
        divider.setStrokeWidth(1.5f * d);
    }

    /** persen: 100 = normal, 140 = paling pekat. */
    public void setSaturation(int percent) {
        sat = Math.max(1f, percent / 100f);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = SystemClock.uptimeMillis() / 1000f;
        float half = w / 2f;

        for (int side = 0; side < 2; side++) {
            c.save();
            c.clipRect(side == 0 ? 0 : half, 0, side == 0 ? half : w, h);
            if (side == 1) {
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(sat);
                p.setColorFilter(new ColorMatrixColorFilter(cm));
            } else {
                p.setColorFilter(null);
            }
            scene(c, w, h, t);
            c.restore();
        }
        p.setColorFilter(null);
        c.drawLine(half, 0, half, h, divider);
        c.drawText("Asli", 8f * d, 16f * d, label);
        c.drawText(sat > 1.001f ? "Dipertajam" : "Sama (mati)", half + 8f * d, 16f * d, label);
        postInvalidateDelayed(33);
    }

    private void scene(Canvas c, float w, float h, float t) {
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF5E8FC0, 0xFFD9B58A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // matahari berdenyut
        float sx = w * (0.5f + 0.38f * (float) Math.sin(t * 0.35f)), sy = h * 0.32f;
        p.setShader(new RadialGradient(sx, sy, 34f * d, 0xFFFFD27A, 0x00FFD27A, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, 34f * d, p);
        // bukit bergeser
        p.setShader(null);
        for (int k = 0; k < 2; k++) {
            hill.reset();
            hill.moveTo(0, h);
            for (float x = 0; x <= w + 6 * d; x += 6 * d) {
                float y = h * (0.68f + 0.1f * k) + (float) Math.sin(x / (46f * d) + t * (0.6f + 0.3f * k) + k * 2f) * 9f * d;
                hill.lineTo(x, y);
            }
            hill.lineTo(w, h);
            hill.close();
            p.setColor(k == 0 ? 0xFF6E9E6A : 0xFF4F8A55);
            c.drawPath(hill, p);
        }
        // bola warna melayang
        for (int i = 0; i < orb.length; i++) {
            float x = w * (0.1f + 0.2f * i) + (float) Math.sin(t * (0.8f + 0.15f * i) + i) * 10f * d;
            float y = h * 0.52f + (float) Math.sin(t * 1.3f + i * 1.7f) * 12f * d;
            p.setColor(orb[i]);
            c.drawCircle(x, y, 9f * d, p);
        }
    }
}
