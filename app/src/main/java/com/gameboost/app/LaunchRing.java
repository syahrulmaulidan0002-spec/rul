package com.gameboost.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Cincin tipis di sekeliling ikon game pada animasi masuk game; terisi sesuai langkah yang selesai. */
public class LaunchRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int c1 = 0xFF0A84FF, c2 = 0xFF64D2FF;
    private SweepGradient sweep;
    private int sweepC1;
    private float sweepW;
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
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        head.setStyle(Paint.Style.FILL);
        head.setColor(0xFFFFFFFF);
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
        c1 = 0xFF30D158;
        c2 = 0xFFA8F5C0;
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
        glow.setStrokeWidth(stroke * 3.4f);
        canvas.drawArc(rect, 0, 360, false, track);
        if (shown > 0.003f) {
            float cx = w / 2f, cy = h / 2f;
            if (sweep == null || sweepC1 != c1 || sweepW != w) {
                sweep = new SweepGradient(cx, cy, new int[]{c1, c2, c1}, new float[]{0f, 0.5f, 1f});
                sweepC1 = c1;
                sweepW = w;
                arc.setShader(sweep);
            }
            glow.setColor((0x38 << 24) | (c2 & 0x00FFFFFF));
            canvas.save();
            canvas.rotate(-90, cx, cy);                 // gradasi mulai dari atas
            canvas.drawArc(rect, 0, 360f * shown, false, glow);
            canvas.drawArc(rect, 0, 360f * shown, false, arc);
            canvas.restore();
            // Titik terang di ujung busur
            double ang = Math.toRadians(-90 + 360f * shown);
            float r = rect.width() / 2f;
            canvas.drawCircle(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r,
                    stroke * 0.9f, head);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
