package com.gameboost.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Ring FPS di panel: busur gradasi hijau-cyan terisi sesuai FPS (penuh di 60), angka di tengah. */
public class FpsRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;

    private int fps = -1;
    private float shown = 0f;
    private ValueAnimator anim;

    public FpsRing(Context c) {
        this(c, null);
    }

    public FpsRing(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(7 * d);
        track.setColor(0x1FFFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(7 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(12 * d);
        glow.setStrokeCap(Paint.Cap.ROUND);
        num.setColor(0xFFFFFFFF);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        num.setTextSize(32 * d);
        lab.setColor(0xB3FFFFFF);
        lab.setTextAlign(Paint.Align.CENTER);
        lab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lab.setTextSize(9.5f * d);
        lab.setLetterSpacing(0.14f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = 9 * d;
        float s = Math.min(w, h);
        float l = (w - s) / 2f + pad, t = (h - s) / 2f + pad;
        rect.set(l, t, l + s - 2 * pad, t + s - 2 * pad);
        applyColor();
    }

    private void applyColor() {
        if (rect.isEmpty()) return;
        if (fps < 0 || fps >= 55) {
            Shader sh = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    0xFF30D158, 0xFF64D2FF, Shader.TileMode.CLAMP);
            arc.setShader(sh);
            glow.setShader(sh);
            glow.setAlpha(70);
        } else {
            int c = fps >= 30 ? 0xFFFF9F0A : 0xFFFF453A;
            arc.setShader(null);
            arc.setColor(c);
            glow.setShader(null);
            glow.setColor((c & 0x00FFFFFF) | 0x46000000);
        }
    }

    /** Nilai baru; busur bergeser halus ke posisinya. */
    public void setFps(int value) {
        boolean first = fps == -1 && value >= 0;
        fps = value;
        applyColor();
        animateTo(value < 0 ? 0f : Math.min(1f, value / 60f), first ? 600 : 420);
        invalidate();
    }

    /** Putar ulang pengisian dari kosong (dipakai saat panel dibuka). */
    public void replay() {
        if (!Motion.enabled(getContext())) return;
        shown = 0f;
        animateTo(fps < 0 ? 0f : Math.min(1f, fps / 60f), 700);
    }

    private void animateTo(float target, long ms) {
        if (anim != null) anim.cancel();
        if (!Motion.enabled(getContext())) {
            shown = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(ms);
        anim.setInterpolator(Motion.IOS);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                shown = (Float) va.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawOval(rect, track);
        if (shown > 0.001f) {
            c.drawArc(rect, -90f, 360f * shown, false, glow);
            c.drawArc(rect, -90f, 360f * shown, false, arc);
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        c.drawText(fps < 0 ? "—" : String.valueOf(fps), cx, cy + 6 * d, num);
        c.drawText("FPS", cx, cy + 22 * d, lab);
    }
}
