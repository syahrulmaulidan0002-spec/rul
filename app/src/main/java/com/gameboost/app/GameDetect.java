package com.gameboost.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** Penentu apakah sebuah aplikasi dianggap game (otomatis atau ditambahkan sendiri ke Game saya). */
final class GameDetect {

    private static final String[] HINTS = {
            "legends", "freefire", "pubg", "codm", "callofduty", "genshin", "supercell",
            "roblox", "minecraft", "garena", "tencent", "mobilelegends", "honkai", "efootball"
    };

    private GameDetect() {}

    /** Terdeteksi sebagai game dari kategori aplikasi atau nama paketnya. */
    static boolean detect(String pkg, ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT >= 26 && ai != null && ai.category == ApplicationInfo.CATEGORY_GAME) {
            return true;
        }
        String p = pkg.toLowerCase(Locale.US);
        for (String h : HINTS) {
            if (p.contains(h)) return true;
        }
        return false;
    }

    /** Dipakai aktif otomatis: game di daftar "Game saya" (terdeteksi atau ditambahkan sendiri). */
    static boolean isMine(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        Set<String> my = Prefs.get(c).getStringSet("my_games", Collections.<String>emptySet());
        if (my.contains(pkg)) return true;
        try {
            return detect(pkg, c.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Exception e) {
            return false;
        }
    }
}
