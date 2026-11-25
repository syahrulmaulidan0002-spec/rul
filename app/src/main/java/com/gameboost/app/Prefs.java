package com.gameboost.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Penyimpanan setelan aplikasi. Semua saklar mode game nyala bawaan, kecuali jendela kecil. */
final class Prefs {

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("gameboost", Context.MODE_PRIVATE);
    }

    /** Kunci: kill, notif, dnd, overlay, freeze, perf, freeform. */
    static boolean flag(Context c, String key) {
        boolean def = !"freeform".equals(key);
        return get(c).getBoolean(key, def);
    }
}
