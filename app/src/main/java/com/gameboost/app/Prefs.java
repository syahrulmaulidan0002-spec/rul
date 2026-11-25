package com.gameboost.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Penyimpanan setelan aplikasi. Semua saklar mode game nyala bawaan, kecuali jendela kecil. */
final class Prefs {

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("gameboost", Context.MODE_PRIVATE);
    }

    /** Kunci: kill, notif, dnd, overlay, freeze, deep, downscale, perf (nyala); freeform, panel_left (mati). */
    static boolean flag(Context c, String key) {
        boolean def = !("freeform".equals(key) || "panel_left".equals(key));
        return get(c).getBoolean(key, def);
    }
}
