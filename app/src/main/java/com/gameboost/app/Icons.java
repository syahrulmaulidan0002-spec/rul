package com.gameboost.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Ikon vektor kecil di samping teks (pengganti emoji dan simbol). */
final class Icons {

    private Icons() {}

    static Drawable get(Context c, int res, int dp, int color) {
        Drawable d = c.getDrawable(res).mutate();
        int px = Math.round(dp * c.getResources().getDisplayMetrics().density);
        d.setBounds(0, 0, px, px);
        d.setTint(color);
        return d;
    }

    static void start(TextView t, int res, int dp, int color) {
        t.setCompoundDrawablesRelative(get(t.getContext(), res, dp, color), null, null, null);
        t.setCompoundDrawablePadding(Math.round(6 * t.getResources().getDisplayMetrics().density));
    }

    static void end(TextView t, int res, int dp, int color) {
        t.setCompoundDrawablesRelative(null, null, get(t.getContext(), res, dp, color), null);
        t.setCompoundDrawablePadding(Math.round(4 * t.getResources().getDisplayMetrics().density));
    }

    static void clear(TextView t) {
        t.setCompoundDrawablesRelative(null, null, null, null);
    }
}
