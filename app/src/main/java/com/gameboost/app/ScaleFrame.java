package com.gameboost.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Pembungkus panel: bila layar terlalu pendek (mode lanskap), seluruh kartu diperkecil secara
 * seragam (bentuk dan isi tetap persis desain) alih-alih dipotong atau dirombak.
 */
public class ScaleFrame extends FrameLayout {

    private float s = 1f;

    public ScaleFrame(Context c) {
        super(c);
    }

    public ScaleFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    /** 0..1; 1 = ukuran asli. */
    public void setCardScale(float v) {
        v = Math.max(0.5f, Math.min(1f, v));
        if (Math.abs(v - s) < 0.01f) return;
        s = v;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        View card = findViewById(R.id.card);
        if (card == null) return;
        card.setPivotX(0f);
        card.setPivotY(0f);
        card.setScaleX(s);
        card.setScaleY(s);
        int w = 0, h = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            if (ch == card) {
                cw = Math.round(cw * s);
                chh = Math.round(chh * s);
            }
            w = Math.max(w, cw);
            h = Math.max(h, chh);
        }
        setMeasuredDimension(w, h);
    }
}
