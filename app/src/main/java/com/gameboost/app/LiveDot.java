package com.gameboost.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Titik hijau "LIVE" yang berdenyut; hanya bergerak saat terlihat. */
public class LiveDot extends View {

    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t = 0f;
    private ValueAnimator anim;

    public LiveDot(Context c) {
        this(c, null);
    }

    public LiveDot(Context c, AttributeSet a) {
        super(c, a);
        dot.setColor(0xFF30D158);
        ring.setColor(0xFF30D158);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * c.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && Motion.enabled(getContext())) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(1400);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator va) {
                        t = (Float) va.getAnimatedValue();
                        invalidate();
                    }
                });
            }
            if (!anim.isStarted()) anim.start();
        } else if (anim != null) {
            anim.cancel();
            anim = null;
            t = 0f;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.55f;
        dot.setAlpha(255 - (int) (80 * Math.abs(0.5f - t) * 2f));
        c.drawCircle(cx, cy, r, dot);
        if (t > 0f) {
            ring.setAlpha((int) (150 * (1f - t)));
            c.drawCircle(cx, cy, r + (Math.min(cx, cy) - r) * t, ring);
        }
    }
}
