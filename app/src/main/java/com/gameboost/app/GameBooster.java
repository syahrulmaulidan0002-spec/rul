package com.gameboost.app;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;

import java.io.DataOutputStream;
import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Inti "mode game": menutup aplikasi latar belakang dan menghening-kan notifikasi.
 *
 * Tanpa root, Android hanya mengizinkan killBackgroundProcesses (proses yang sedang di
 * latar belakang). Jika HP di-root, aplikasi juga memakai "am force-stop" yang lebih tuntas.
 */
final class GameBooster {

    private static final String STATE = "gb_state";
    private static final String KEY_PREV_FILTER = "prev_filter";
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");

    private GameBooster() {}

    static final class Result {
        int requested;     // jumlah aplikasi yang diperintah berhenti
        long freedMb;      // RAM yang bertambah lega
        boolean viaRoot;   // true jika force-stop via root berhasil
    }

    // ---------------------------------------------------------------- RAM / proses

    /** Pembersihan penuh + ukur RAM. Jalankan di thread background (ada jeda ~0,8 detik). */
    static Result boost(Context ctx, String gamePkg) {
        Context app = ctx.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> targets = targets(app, gamePkg);

        long before = availMb(am);
        killAll(am, targets);

        Result res = new Result();
        res.requested = targets.size();
        res.viaRoot = hasSuBinary() && forceStopAsRoot(targets);

        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        res.freedMb = Math.max(0, availMb(am) - before);
        return res;
    }

    /** Penutupan cepat tanpa jeda, dipakai berulang selama game berjalan agar tidak hidup lagi. */
    static void quickKill(Context ctx, String gamePkg) {
        Context app = ctx.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        killAll(am, targets(app, gamePkg));
    }

    private static void killAll(ActivityManager am, Collection<String> pkgs) {
        for (String p : pkgs) {
            try {
                am.killBackgroundProcesses(p);
            } catch (Exception ignored) {
            }
        }
    }

    /** Semua aplikasi pengguna yang boleh ditutup (bukan game dan bukan aplikasi penting). */
    private static Set<String> targets(Context app, String gamePkg) {
        PackageManager pm = app.getPackageManager();
        Set<String> keep = protectedPackages(app, gamePkg);

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Set<String> targets = new LinkedHashSet<>();
        for (ResolveInfo ri : apps) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String p = ai.packageName;
            if (keep.contains(p)) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (system && !updated) continue;   // aplikasi inti sistem jangan disentuh
            targets.add(p);
        }
        return targets;
    }

    private static long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024L * 1024L);
    }

    /** Aplikasi yang tidak boleh dihentikan: game, launcher, keyboard, telepon, SMS, GameBoost. */
    private static Set<String> protectedPackages(Context ctx, String gamePkg) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> keep = new HashSet<>();
        keep.add(ctx.getPackageName());
        if (gamePkg != null) keep.add(gamePkg);
        keep.add("android");
        keep.add("com.android.systemui");
        keep.add("com.android.phone");
        keep.add("com.android.server.telecom");
        keep.add("com.android.settings");
        keep.add("com.android.dialer");
        keep.add("com.google.android.dialer");
        keep.add("com.google.android.gms");
        keep.add("com.google.android.gsf");
        // HyperOS/MIUI: pusat keamanan dan manajer daya jangan dimatikan
        keep.add("com.miui.securitycenter");
        keep.add("com.miui.powerkeeper");
        keep.add("com.xiaomi.xmsf");
        keep.add("com.miui.home");

        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) keep.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {
        }
        try {
            Intent dial = new Intent(Intent.ACTION_DIAL);
            ResolveInfo ri = pm.resolveActivity(dial, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) keep.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {
        }
        try {
            String ime = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName cn = ime != null ? ComponentName.unflattenFromString(ime) : null;
            if (cn != null) keep.add(cn.getPackageName());
        } catch (Exception ignored) {
        }
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(ctx);
            if (sms != null) keep.add(sms);
        } catch (Exception ignored) {
        }
        return keep;
    }

    // ---------------------------------------------------------------- root (opsional)

    private static boolean hasSuBinary() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/data/adb/magisk"};
        for (String p : paths) {
            if (new File(p).exists()) return true;
        }
        return false;
    }

    private static boolean forceStopAsRoot(Collection<String> pkgs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String s : pkgs) {
                if (SAFE_PKG.matcher(s).matches()) {
                    os.writeBytes("am force-stop " + s + "\n");
                }
            }
            os.writeBytes("exit\n");
            os.flush();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ---------------------------------------------------------------- Jangan Ganggu

    static boolean hasDndAccess(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    /**
     * Nyalakan "Alarm saja": notifikasi, panggilan, dan pesan dibisukan total tanpa pop-up.
     * Setelan sebelumnya diingat dan dikembalikan saat mode game berakhir.
     */
    static void enableDnd(Context ctx) {
        if (!hasDndAccess(ctx)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        SharedPreferences sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_PREV_FILTER)) {
            sp.edit().putInt(KEY_PREV_FILTER, nm.getCurrentInterruptionFilter()).apply();
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
    }

    /** Kembalikan Jangan Ganggu ke keadaan sebelum mode game (hanya jika kita yang mengubahnya). */
    static void restoreDnd(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_PREV_FILTER) || !hasDndAccess(ctx)) return;
        int prev = sp.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL);
        if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            prev = NotificationManager.INTERRUPTION_FILTER_ALL;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.setInterruptionFilter(prev);
        sp.edit().remove(KEY_PREV_FILTER).apply();
    }
}
