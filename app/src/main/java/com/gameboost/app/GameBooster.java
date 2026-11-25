package com.gameboost.app;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.Display;
import android.view.WindowManager;

import java.io.DataOutputStream;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Inti "mode game": menutup/membekukan aplikasi latar belakang, mode performa, dan Jangan Ganggu.
 *
 * Urutan cara terkuat yang dipakai otomatis:
 *  1. Shizuku (hak shell, atau root bila Shizuku dinyalakan lewat root): force-stop dan suspend.
 *  2. Root langsung (su): force-stop.
 *  3. Tanpa keduanya: killBackgroundProcesses (hanya aplikasi yang sedang di latar belakang).
 */
final class GameBooster {

    private static final String STATE = "gb_state";
    private static final String KEY_PREV_FILTER = "prev_filter";
    private static final String KEY_SUSPENDED = "suspended";
    private static final String KEY_PERF_ACTIVE = "perf_active";
    private static final String KEY_PERF_GAME = "perf_game";
    private static final String KEY_PREV_MIN_RR = "prev_min_rr";
    private static final String KEY_PREV_PEAK_RR = "prev_peak_rr";
    private static final String KEY_RR_CHANGED = "rr_changed";
    private static final String KEY_PREV_LOW_POWER = "prev_low_power";
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");

    private GameBooster() {}

    static final class Result {
        int requested;      // jumlah aplikasi yang diperintah berhenti
        int frozen;         // jumlah aplikasi yang berhasil dibekukan (suspend)
        long freedMb;       // RAM yang bertambah lega
        String method = "standar";
    }

    // ---------------------------------------------------------------- tutup / bekukan

    /** Pembersihan penuh + ukur RAM. Jalankan di thread background (ada jeda ~0,8 detik). */
    static Result boost(Context ctx, String gamePkg, boolean freeze) {
        Context app = ctx.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> targets = targets(app, gamePkg);

        Result res = new Result();
        res.requested = targets.size();
        long before = availMb(am);

        if (ShizukuHelper.ready()) {
            StringBuilder sb = new StringBuilder();
            for (String p : targets) {
                if (!SAFE_PKG.matcher(p).matches()) continue;
                sb.append("am force-stop ").append(p).append('\n');
                if (freeze) sb.append("pm suspend --user 0 ").append(p).append('\n');
            }
            if (freeze) rememberSuspended(app, targets);
            String out = ShizukuHelper.exec(sb.toString());
            if (out != null) {
                res.method = ShizukuHelper.isRoot() ? "Shizuku (root)" : "Shizuku";
                if (freeze) res.frozen = countOccurrences(out, "suspended state: true");
            }
        }

        killAll(am, targets);

        if ("standar".equals(res.method) && hasSuBinary() && forceStopAsRoot(targets)) {
            res.method = "root";
        }

        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        res.freedMb = Math.max(0, availMb(am) - before);
        return res;
    }

    /** Penutupan cepat tanpa jeda, dipakai berulang selama game berjalan. */
    static void quickKill(Context ctx, String gamePkg, boolean frozen) {
        Context app = ctx.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> targets = targets(app, gamePkg);
        killAll(am, targets);
        // Aplikasi yang dibekukan tidak bisa hidup lagi; selain itu, hentikan ulang lewat Shizuku.
        if (!frozen && ShizukuHelper.ready()) {
            StringBuilder sb = new StringBuilder();
            for (String p : targets) {
                if (SAFE_PKG.matcher(p).matches()) sb.append("am force-stop ").append(p).append('\n');
            }
            ShizukuHelper.exec(sb.toString());
        }
    }

    private static int countOccurrences(String text, String what) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(what, i)) >= 0) {
            n++;
            i += what.length();
        }
        return n;
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

    /** Aplikasi yang tidak boleh disentuh: game, launcher, keyboard, telepon, SMS, Shizuku, GameBoost. */
    private static Set<String> protectedPackages(Context ctx, String gamePkg) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> keep = new HashSet<>();
        keep.add(ctx.getPackageName());
        if (gamePkg != null) keep.add(gamePkg);
        keep.add(ShizukuHelper.SHIZUKU_PKG);
        keep.add("android");
        keep.add("com.android.systemui");
        keep.add("com.android.phone");
        keep.add("com.android.server.telecom");
        keep.add("com.android.settings");
        keep.add("com.android.dialer");
        keep.add("com.google.android.dialer");
        keep.add("com.google.android.gms");
        keep.add("com.google.android.gsf");
        keep.add("com.android.vending");              // Play Store: sebagian game memeriksa lisensi
        keep.add("com.google.android.deskclock");     // jam & alarm tetap boleh berbunyi
        keep.add("com.android.deskclock");
        // HyperOS/MIUI: pusat keamanan dan manajer daya jangan disentuh
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

    // ---------------------------------------------------------------- pemulihan

    private static SharedPreferences state(Context c) {
        return c.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static void rememberSuspended(Context c, Collection<String> pkgs) {
        SharedPreferences sp = state(c);
        Set<String> all = new HashSet<>(sp.getStringSet(KEY_SUSPENDED, Collections.<String>emptySet()));
        all.addAll(pkgs);
        sp.edit().putStringSet(KEY_SUSPENDED, all).apply();
    }

    /** Jumlah aplikasi yang masih tercatat dibekukan dan belum dipulihkan. */
    static int pendingCount(Context c) {
        return state(c).getStringSet(KEY_SUSPENDED, Collections.<String>emptySet()).size();
    }

    /**
     * Pulihkan semua aplikasi yang dibekukan dan kembalikan setelan performa.
     * Mengembalikan jumlah aplikasi yang dipulihkan, atau -1 jika Shizuku belum siap / gagal.
     * Jalankan di thread background.
     */
    static synchronized int restoreSession(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = state(app);
        Set<String> frozen = new HashSet<>(sp.getStringSet(KEY_SUSPENDED, Collections.<String>emptySet()));
        boolean perf = sp.getBoolean(KEY_PERF_ACTIVE, false);
        if (frozen.isEmpty() && !perf) return 0;
        if (!ShizukuHelper.ready()) return -1;

        StringBuilder sb = new StringBuilder();
        for (String p : frozen) {
            if (SAFE_PKG.matcher(p).matches()) sb.append("pm unsuspend --user 0 ").append(p).append('\n');
        }
        if (perf) appendPerfRestore(sp, sb);

        String out = ShizukuHelper.exec(sb.toString());
        if (out == null) return -1;
        sp.edit().remove(KEY_SUSPENDED).putBoolean(KEY_PERF_ACTIVE, false)
                .putBoolean(KEY_RR_CHANGED, false).apply();
        return frozen.size();
    }

    // ---------------------------------------------------------------- mode performa

    private static float maxRefreshRate(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            float max = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= 23) {
                for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
            }
            return max;
        } catch (Exception e) {
            return 60f;
        }
    }

    /**
     * Mode performa lewat Shizuku: Game Mode "performa" untuk game, kunci refresh rate layar ke
     * maksimal, dan matikan penghemat daya. Semua setelan semula disimpan lalu dikembalikan.
     * Tidak menyentuh pelindung suhu HP.
     */
    static void enablePerf(Context ctx, String gamePkg) {
        if (!ShizukuHelper.ready() || gamePkg == null || !SAFE_PKG.matcher(gamePkg).matches()) return;
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = state(app);
        ContentResolver cr = app.getContentResolver();
        boolean already = sp.getBoolean(KEY_PERF_ACTIVE, false);

        StringBuilder sb = new StringBuilder();
        SharedPreferences.Editor ed = sp.edit();

        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("cmd game mode performance ").append(gamePkg).append('\n');
        }

        float max = maxRefreshRate(app);
        if (max > 61f) {
            if (!already) {
                ed.putBoolean(KEY_RR_CHANGED, true);
                ed.putFloat(KEY_PREV_MIN_RR, Settings.System.getFloat(cr, "min_refresh_rate", -1f));
                ed.putFloat(KEY_PREV_PEAK_RR, Settings.System.getFloat(cr, "peak_refresh_rate", -1f));
            }
            int hz = Math.round(max);
            sb.append("settings put system min_refresh_rate ").append(hz).append('\n');
            sb.append("settings put system peak_refresh_rate ").append(hz).append('\n');
        }

        if (!already) {
            int low = Settings.Global.getInt(cr, "low_power", 0);
            ed.putInt(KEY_PREV_LOW_POWER, low);
        }
        sb.append("settings put global low_power 0\n");

        ed.putString(KEY_PERF_GAME, gamePkg);
        ed.putBoolean(KEY_PERF_ACTIVE, true);
        ed.apply();

        ShizukuHelper.exec(sb.toString());
    }

    private static void appendPerfRestore(SharedPreferences sp, StringBuilder sb) {
        String game = sp.getString(KEY_PERF_GAME, null);
        if (Build.VERSION.SDK_INT >= 31 && game != null && SAFE_PKG.matcher(game).matches()) {
            sb.append("cmd game reset ").append(game).append('\n');
        }
        if (sp.getBoolean(KEY_RR_CHANGED, false)) {
            appendRestoreSetting(sb, "min_refresh_rate", sp.getFloat(KEY_PREV_MIN_RR, -1f));
            appendRestoreSetting(sb, "peak_refresh_rate", sp.getFloat(KEY_PREV_PEAK_RR, -1f));
        }
        if (sp.getInt(KEY_PREV_LOW_POWER, 0) == 1) {
            sb.append("settings put global low_power 1\n");
        }
    }

    private static void appendRestoreSetting(StringBuilder sb, String key, float prev) {
        if (prev < 0f) {
            sb.append("settings delete system ").append(key).append('\n');
        } else {
            sb.append(String.format(Locale.US, "settings put system %s %.1f\n", key, prev));
        }
    }

    // ---------------------------------------------------------------- root (cadangan)

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
        SharedPreferences sp = state(ctx);
        if (!sp.contains(KEY_PREV_FILTER)) {
            sp.edit().putInt(KEY_PREV_FILTER, nm.getCurrentInterruptionFilter()).apply();
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
    }

    /** Kembalikan Jangan Ganggu ke keadaan sebelum mode game (hanya jika kita yang mengubahnya). */
    static void restoreDnd(Context ctx) {
        SharedPreferences sp = state(ctx);
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
