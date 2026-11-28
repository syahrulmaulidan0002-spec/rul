package com.gameboost.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Pembungkus panel: kartu selalu diukur sebesar isinya (tinggi tak dibatasi), lalu bila layar terlalu
 * pendek (mode lanskap) seluruh kartu diperkecil seragam. Isi tidak pernah terjepit atau terpotong.
 */
public class ScaleFrame extends FrameLayout {

    public ScaleFrame(Context c) {
        super(c);
    }

    public ScaleFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        View card = findViewById(R.id.card);
        int w = 0, h = 0;
        int availH = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(hSpec);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            if (ch == card) {
                ch.measure(getChildMeasureSpec(wSpec, 0, ch.getLayoutParams().width),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int ch0 = ch.getMeasuredHeight();
                float s = 1f;
                if (availH != Integer.MAX_VALUE && ch0 > 0) {
                    s = Math.max(0.5f, Math.min(1f, (availH - 16f * d) / ch0));
                }
                ch.setPivotX(0f);
                ch.setPivotY(0f);
                ch.setScaleX(s);
                ch.setScaleY(s);
                w = Math.max(w, Math.round(ch.getMeasuredWidth() * s));
                h = Math.max(h, Math.round(ch0 * s));
            } else {
                measureChild(ch, wSpec, hSpec);
                w = Math.max(w, ch.getMeasuredWidth());
                h = Math.max(h, ch.getMeasuredHeight());
            }
        }
        setMeasuredDimension(w, h);
    }
}
