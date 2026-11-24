package com.gameboost.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Cincin progres ala iOS (Activity Ring) untuk memperlihatkan pemakaian RAM. */
public class RingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float shown = 0f;
    private int target = -1;
    private ValueAnimator anim;

    public RingView(Context c) {
        this(c, null);
    }

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0xFF2C2C2E);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFF30D158);
        big.setColor(Color.WHITE);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(0xFF8E8E93);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    /** Atur nilai (0-100) dan warna cincin; perubahan nilai dianimasikan halus. */
    public void setValue(int pct, int color) {
        pct = Math.max(0, Math.min(100, pct));
        arc.setColor(color);
        if (pct != target) {
            target = pct;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(shown, pct);
            anim.setDuration(550);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    shown = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float stroke = size * 0.12f;
        float pad = stroke / 2f + size * 0.02f;
        rect.set((w - size) / 2f + pad, (h - size) / 2f + pad,
                (w + size) / 2f - pad, (h + size) / 2f - pad);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);

        canvas.drawArc(rect, 0, 360, false, track);
        if (shown > 0.5f) {
            canvas.drawArc(rect, -90, 360f * shown / 100f, false, arc);
        }

        big.setTextSize(size * 0.25f);
        small.setTextSize(size * 0.12f);
        float cx = w / 2f;
        float cy = h / 2f;
        canvas.drawText(Math.round(shown) + "%", cx,
                cy - (big.descent() + big.ascent()) / 2f - size * 0.04f, big);
        canvas.drawText("RAM", cx, cy + size * 0.22f, small);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
