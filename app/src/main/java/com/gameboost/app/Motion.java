package com.gameboost.app;

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;

/** Gerak bergaya iOS yang dipakai di seluruh aplikasi: satu kurva, durasi pendek, ringan untuk HP. */
final class Motion {

    /** Cepat di awal, melambat halus di akhir (0.32, 0.72, 0, 1). */
    static final Interpolator IOS = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    static final Interpolator SPRING = new OvershootInterpolator(1.6f);

    private Motion() {}

    /** False bila pengguna mematikan animasi sistem (aksesibilitas): semua gerak dilewati. */
    static boolean enabled(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) {
            return true;
        }
    }

    /** Naik 14 dp sambil memudar masuk, dengan jeda. */
    static void rise(View v, long delayMs) {
        if (!enabled(v.getContext())) return;
        float d = v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(14f * d);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(380)
                .setInterpolator(IOS).start();
    }

    /** Anak-anak muncul berurutan (jeda 40 ms), maksimal 12 supaya tidak terasa lambat. */
    static void stagger(ViewGroup parent, long baseDelay) {
        int n = 0;
        for (int i = 0; i < parent.getChildCount() && n < 12; i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            rise(c, baseDelay + n * 40L);
            n++;
        }
    }

    /** Menyusut saat ditekan, memantul kembali saat dilepas. Tidak mengganggu klik. */
    static void press(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (!enabled(view.getContext())) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.95f).scaleY(0.95f).setStartDelay(0).setDuration(90)
                                .setInterpolator(IOS).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(200)
                                .setInterpolator(SPRING).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /** Pasang efek tekan ke semua view yang bisa diketuk (kecuali slider dan saklar). */
    static void pressAll(View root) {
        if (root instanceof AbsSeekBar || root instanceof CompoundButton) return;
        if (root.isClickable() && !(root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 12)) {
            press(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) pressAll(g.getChildAt(i));
        }
    }
}
