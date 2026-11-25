package com.gameboost.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Ikon baterai ala iOS: badan bulat, isi sesuai level, dan petir saat mengisi daya. */
public class BatteryView extends View {

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF inner = new RectF();
    private final RectF tip = new RectF();
    private final Path boltPath = new Path();

    private int level = 0;
    private boolean charging = false;

    public BatteryView(Context c) {
        this(c, null);
    }

    public BatteryView(Context c, AttributeSet a) {
        super(c, a);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0x99FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        nub.setStyle(Paint.Style.FILL);
        nub.setColor(0x99FFFFFF);
        bolt.setStyle(Paint.Style.FILL);
        bolt.setColor(0xFFFFFFFF);
    }

    public void setLevel(int pct, boolean charging) {
        this.level = Math.max(0, Math.min(100, pct));
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float stroke = Math.max(1f, h * 0.1f);
        float nubW = w * 0.07f;
        outline.setStrokeWidth(stroke);

        body.set(stroke / 2f, stroke / 2f, w - nubW - 1f - stroke / 2f, h - stroke / 2f);
        float r = h * 0.28f;
        canvas.drawRoundRect(body, r, r, outline);

        tip.set(w - nubW, h * 0.34f, w, h * 0.66f);
        canvas.drawRoundRect(tip, nubW / 2f, nubW / 2f, nub);

        int color = charging ? 0xFF30D158 : (level <= 20 ? 0xFFFF453A : 0xFFFFFFFF);
        fill.setColor(color);
        float gap = stroke * 1.1f;
        float left = body.left + stroke / 2f + gap;
        float right = body.right - stroke / 2f - gap;
        float full = Math.max(0f, right - left);
        inner.set(left, body.top + stroke / 2f + gap, left + full * level / 100f,
                body.bottom - stroke / 2f - gap);
        if (inner.width() > 0.5f) {
            float ir = Math.max(1f, r - gap);
            canvas.drawRoundRect(inner, ir, ir, fill);
        }

        if (charging) {
            float cx = body.centerX();
            float cy = body.centerY();
            float bh = h * 0.62f;
            float bw = bh * 0.55f;
            boltPath.reset();
            boltPath.moveTo(cx + bw * 0.1f, cy - bh / 2f);
            boltPath.lineTo(cx - bw / 2f, cy + bh * 0.08f);
            boltPath.lineTo(cx - bw * 0.02f, cy + bh * 0.08f);
            boltPath.lineTo(cx - bw * 0.1f, cy + bh / 2f);
            boltPath.lineTo(cx + bw / 2f, cy - bh * 0.08f);
            boltPath.lineTo(cx + bw * 0.02f, cy - bh * 0.08f);
            boltPath.close();
            canvas.drawPath(boltPath, bolt);
        }
    }
}
