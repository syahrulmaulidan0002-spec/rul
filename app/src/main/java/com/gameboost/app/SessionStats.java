package com.gameboost.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Menyimpan ringkasan sesi terakhir (untuk kartu di beranda dan notifikasi ringkasan). */
final class SessionStats {

    private static final String P = "gb_session";

    private SessionStats() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static void save(Context c, String title, String body, long whenMs) {
        sp(c).edit().putString("title", title).putString("body", body).putLong("when", whenMs).apply();
    }

    static String title(Context c) { return sp(c).getString("title", null); }

    static String body(Context c) { return sp(c).getString("body", ""); }

    static long when(Context c) { return sp(c).getLong("when", 0); }

    static String duration(long ms) {
        long min = Math.max(0, ms / 60000L);
        if (min < 1) return String.format(Locale.US, "%d dtk", Math.max(1, ms / 1000L));
        if (min < 60) return min + " menit";
        return String.format(Locale.US, "%d j %02d mnt", min / 60, min % 60);
    }
}
