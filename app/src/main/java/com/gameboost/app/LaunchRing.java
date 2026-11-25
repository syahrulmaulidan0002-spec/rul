package com.gameboost.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Cincin tipis di sekeliling ikon game pada animasi masuk game; terisi sesuai langkah yang selesai. */
public class LaunchRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float shown = 0f;       // 0..1
    private ValueAnimator anim;

    public LaunchRing(Context c) {
        this(c, null);
    }

    public LaunchRing(Context c, AttributeSet a) {
        super(c, a);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0xFF2C2C2E);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFF0A84FF);
    }

    public void setProgress(float target) {
        target = Math.max(0f, Math.min(1f, target));
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(380);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    /** Selesai: cincin penuh dan berwarna hijau. */
    public void setDone() {
        arc.setColor(0xFF30D158);
        setProgress(1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float stroke = size * 0.028f;
        float pad = stroke / 2f + 1f;
        rect.set((w - size) / 2f + pad, (h - size) / 2f + pad,
                (w + size) / 2f - pad, (h + size) / 2f - pad);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        canvas.drawArc(rect, 0, 360, false, track);
        if (shown > 0.003f) canvas.drawArc(rect, -90, 360f * shown, false, arc);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
