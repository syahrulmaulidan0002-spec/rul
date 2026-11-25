package com.gameboost.app;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

/** Pengecekan dan pembuka halaman izin yang dipakai beranda dan halaman Pengaturan. */
final class PermHelper {

    private PermHelper() {}

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static boolean hasNotifPerm(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean ignoresBatteryOpt(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void requestIgnoreBattery(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /** Akses penggunaan: dipakai untuk mengetahui game mana yang sedang dibuka (aktif otomatis). */
    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static void openUsageAccess(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    /** Daftar izin yang masih kurang untuk setelan sekarang, atau null jika sudah lengkap. */
    static String missing(Context c) {
        java.util.List<String> m = new java.util.ArrayList<>();
        if (Prefs.flag(c, "overlay") && !canOverlay(c)) m.add("Jendela mengambang");
        if (Prefs.flag(c, "dnd") && Build.VERSION.SDK_INT >= 23 && !GameBooster.hasDndAccess(c)) {
            m.add("Jangan Ganggu");
        }
        if (Prefs.flag(c, "notif") && !GameNotifService.isEnabled(c)) m.add("Akses notifikasi");
        if (m.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(m.get(i));
        }
        return sb.toString();
    }

    static void openOverlaySettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }
}
