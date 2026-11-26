package com.gameboost.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Latar "liquid glass": kaca tipis yang tembus pandang, dengan pantulan terang di sudut kiri atas,
 * kilau tipis di sudut kanan bawah, dan pendar lembut di sisi atas.
 */
final class GlassDrawable extends Drawable {

    private final float radius, density;
    private final int tint;          // warna dasar (RGB)
    private final float fillAlpha;   // 0..1, seberapa pekat isian kaca
    private final float rim;         // 0..1, kekuatan pantulan tepi

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    GlassDrawable(Context c, float radiusDp, int tintRgb, float fillAlpha, float rim) {
        density = c.getResources().getDisplayMetrics().density;
        radius = radiusDp * density;
        tint = tintRgb & 0x00FFFFFF;
        this.fillAlpha = Math.max(0f, Math.min(1f, fillAlpha));
        this.rim = Math.max(0f, Math.min(1f, rim));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(((int) (this.fillAlpha * 255) << 24) | tint);
        glow.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(2f * density);
        inner.setColor(((int) (0x16 * this.rim) << 24) | 0xFFFFFF);
    }

    private static int white(float a) {
        return ((int) (Math.max(0f, Math.min(1f, a)) * 255) << 24) | 0xFFFFFF;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        // Pendar atas: terang di puncak, hilang ke tengah.
        glow.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                white(0.20f * rim), white(0f), Shader.TileMode.CLAMP));
        // Tepi: pantulan kuat kiri atas, kilau kecil kanan bawah.
        edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{white(0.85f * rim), white(0.10f * rim), white(0f), white(0.38f * rim)},
                new float[]{0f, 0.32f, 0.66f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        rf.set(b);
        canvas.drawRoundRect(rf, radius, radius, fill);
        canvas.drawRoundRect(rf, radius, radius, glow);
        float h = edge.getStrokeWidth() / 2f;
        rf.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
        canvas.drawRoundRect(rf, radius - h, radius - h, edge);
        float i = edge.getStrokeWidth() + inner.getStrokeWidth() / 2f;
        rf.set(b.left + i, b.top + i, b.right - i, b.bottom - i);
        canvas.drawRoundRect(rf, Math.max(0f, radius - i), Math.max(0f, radius - i), inner);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
