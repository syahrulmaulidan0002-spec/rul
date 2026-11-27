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
    private static final String KEY_SAT_ACTIVE = "sat_active";
    static final String PREF_SAT = "sat";      // 100 = normal, 100..140 = lebih tajam
    private static final String KEY_PREV_MIN_RR = "prev_min_rr";
    private static final String KEY_PREV_PEAK_RR = "prev_peak_rr";
    private static final String KEY_NET_ACTIVE = "net_active";
    private static final String KEY_NET_PREV = "net_prev_restrict";
    private static final String KEY_NET_UID = "net_uid_added";
    private static final String KEY_RR_CHANGED = "rr_changed";
    private static final String KEY_PREV_LOW_POWER = "prev_low_power";
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");

    /** RAM bebas sebelum/sesudah pembersihan terakhir (untuk animasi). */
    static volatile long lastBeforeMb, lastAfterMb;

    static long availMbNow(Context c) {
        return availMb((ActivityManager) c.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE));
    }

    private GameBooster() {}

    static final class Result {
        int requested;      // jumlah aplikasi yang diperintah berhenti
        int frozen;         // jumlah aplikasi yang berhasil dibekukan (suspend)
        long freedMb;       // RAM yang bertambah lega
        String method = "standar";
    }

    // ---------------------------------------------------------------- tutup / bekukan

    /** Pembersihan penuh + ukur RAM. Jalankan di thread background (ada jeda ~0,8 detik). */
    static synchronized Result boost(Context ctx, String gamePkg, boolean freeze) {
        Context app = ctx.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> targets = targets(app, gamePkg);

        Result res = new Result();
        res.requested = targets.size();
        long before = availMb(am);

        if (ShizukuHelper.ready()) {
            StringBuilder sb = new StringBuilder();
            // Satu per satu terlalu lambat di HP lama (tiap perintah memulai proses baru),
            // jadi dijalankan paralel 6 aplikasi sekaligus.
            int n = 0;
            for (String p : targets) {
                if (!SAFE_PKG.matcher(p).matches()) continue;
                sb.append("(am force-stop ").append(p);
                if (freeze) sb.append("; pm suspend --user 0 ").append(p);
                sb.append(") &\n");
                if (++n % 6 == 0) sb.append("wait\n");
            }
            sb.append("wait\n");
            if (freeze) rememberSuspended(app, targets);
            if (freeze) sb.append("am kill-all\n");          // sisa proses cache ikut dimatikan
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
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        res.freedMb = Math.max(0, availMb(am) - before);
        lastBeforeMb = before;
        lastAfterMb = before + res.freedMb;
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
    static Set<String> targets(Context app, String gamePkg) {
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

        // Mode "sampai ke akar": semua aplikasi pihak ketiga, termasuk yang tidak punya ikon
        // peluncur tetapi diam-diam menjalankan layanan di latar belakang.
        if (deepMode(app)) {
            String out = ShizukuHelper.exec("pm list packages -3");
            if (out != null) {
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (!line.startsWith("package:")) continue;
                    String p = line.substring(8).trim();
                    if (!keep.contains(p) && SAFE_PKG.matcher(p).matches()) targets.add(p);
                }
            }
        }
        return targets;
    }

    /** Mode "sampai ke akar" hanya berlaku jika Shizuku siap, bekukan nyala, dan saklarnya nyala. */
    private static boolean deepMode(Context app) {
        return Prefs.flag(app, "deep") && Prefs.flag(app, "freeze") && ShizukuHelper.ready();
    }

    /** Ambil nama paket dari daftar "paket/kelas:paket/kelas" milik setelan sistem. */
    private static void addPackagesFrom(Context ctx, String key, Set<String> keep) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), key);
            if (v == null) return;
            for (String part : v.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (cn != null) keep.add(cn.getPackageName());
            }
        } catch (Exception ignored) {
        }
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
        addPackagesFrom(ctx, "enabled_accessibility_services", keep);   // mis. pembaca layar
        addPackagesFrom(ctx, "assistant", keep);
        try {
            String imes = Settings.Secure.getString(ctx.getContentResolver(), "enabled_input_methods");
            if (imes != null) {
                for (String part : imes.split(":")) {
                    int slash = part.indexOf('/');
                    if (slash > 0) keep.add(part.substring(0, slash));
                }
            }
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
        boolean net = sp.getBoolean(KEY_NET_ACTIVE, false);
        if (frozen.isEmpty() && !perf && !net) return 0;
        if (!ShizukuHelper.ready()) return -1;

        StringBuilder sb = new StringBuilder();
        // Setelan dulu (cepat), lalu aplikasi dibuka bekunya paralel 8 sekaligus: satu per satu
        // terlalu lambat di HP lama karena tiap perintah memulai proses baru.
        if (perf) appendPerfRestore(sp, sb);
        if (net) {
            if (!sp.getBoolean(KEY_NET_PREV, false)) sb.append("cmd netpolicy set restrict-background false\n");
            int uid = sp.getInt(KEY_NET_UID, -1);
            if (uid >= 0) sb.append("cmd netpolicy remove restrict-background-whitelist ").append(uid).append('\n');
        }
        int n = 0;
        for (String p : frozen) {
            if (!SAFE_PKG.matcher(p).matches()) continue;
            sb.append("pm unsuspend --user 0 ").append(p).append(" >/dev/null &\n");
            if (++n % 8 == 0) sb.append("wait\n");
        }
        sb.append("wait\n");

        String out = ShizukuHelper.exec(sb.toString());
        if (out == null) return -1;
        // Hanya hapus yang barusan dipulihkan; catatan baru dari sesi lain tidak boleh ikut hilang.
        Set<String> left = new HashSet<>(sp.getStringSet(KEY_SUSPENDED, Collections.<String>emptySet()));
        left.removeAll(frozen);
        sp.edit().putStringSet(KEY_SUSPENDED, left).putBoolean(KEY_PERF_ACTIVE, false)
                .putBoolean(KEY_RR_CHANGED, false).putBoolean(KEY_SAT_ACTIVE, false)
                .putBoolean(KEY_NET_ACTIVE, false).apply();
        return frozen.size();
    }

    // ---------------------------------------------------------------- fokus internet

    /**
     * Putuskan internet latar belakang: nyalakan Penghemat Data sistem dan beri pengecualian hanya
     * untuk game. Penghemat Data berlaku untuk data seluler. Aplikasi yang dibekukan sudah tidak
     * bisa memakai internet sama sekali (tidak berjalan). Setelan semula dikembalikan saat selesai.
     * Jalankan di thread background. Mengembalikan true jika perintahnya terkirim.
     */
    static boolean enableNetFocus(Context ctx, String gamePkg) {
        if (!ShizukuHelper.ready() || gamePkg == null) return false;
        Context app = ctx.getApplicationContext();
        int uid;
        try {
            uid = app.getPackageManager().getApplicationInfo(gamePkg, 0).uid;
        } catch (Exception e) {
            return false;
        }
        SharedPreferences sp = state(app);
        boolean already = sp.getBoolean(KEY_NET_ACTIVE, false);

        boolean wasOn = false;
        boolean whitelisted = false;
        if (!already) {
            String cur = ShizukuHelper.exec("cmd netpolicy get restrict-background");
            if (cur == null) return false;
            wasOn = cur.toLowerCase(Locale.US).contains("enabled");
            String list = ShizukuHelper.exec("cmd netpolicy list restrict-background-whitelist");
            whitelisted = list != null && Pattern.compile("\\b" + uid + "\\b").matcher(list).find();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("cmd netpolicy set restrict-background true\n");
        if (!already && !whitelisted) sb.append("cmd netpolicy add restrict-background-whitelist ").append(uid).append('\n');
        if (ShizukuHelper.exec(sb.toString()) == null) return false;

        if (!already) {
            sp.edit().putBoolean(KEY_NET_ACTIVE, true).putBoolean(KEY_NET_PREV, wasOn)
                    .putInt(KEY_NET_UID, whitelisted ? -1 : uid).apply();
        }
        return true;
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

        if (Build.VERSION.SDK_INT >= 33 && Prefs.flag(app, "downscale")) {
            // Android 13+: render game di resolusi 80% lalu diperbesar ke layar. Beban GPU turun,
            // FPS naik. Dikembalikan lewat "cmd game reset".
            sb.append("cmd game set --mode performance --downscale 0.8 ").append(gamePkg).append('\n');
        } else if (Build.VERSION.SDK_INT >= 31) {
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

        int sat = Prefs.get(app).getInt(PREF_SAT, 100);
        if (sat > 100 && sat <= 160) {
            // Warna lebih pekat selama game (SurfaceFlinger). Dikembalikan ke 1.0 saat selesai.
            sb.append(String.format(Locale.US, "service call SurfaceFlinger 1022 f %.2f\n", sat / 100f));
            ed.putBoolean(KEY_SAT_ACTIVE, true);
        }

        ed.putString(KEY_PERF_GAME, gamePkg);
        ed.putBoolean(KEY_PERF_ACTIVE, true);
        ed.apply();

        ShizukuHelper.exec(sb.toString());
    }

    /** Kembalikan warna layar ke normal sekarang (tombol "Reset warna"). Jalankan di thread background. */
    static boolean resetSaturation(Context ctx) {
        if (!ShizukuHelper.ready()) return false;
        String out = ShizukuHelper.exec("service call SurfaceFlinger 1022 f 1.0\n");
        if (out != null) state(ctx.getApplicationContext()).edit().putBoolean(KEY_SAT_ACTIVE, false).apply();
        return out != null;
    }

    /**
     * Saat merekam, resolusi game dikembalikan 100% (mode performa tetap), supaya rekaman tajam.
     * Setelah rekaman selesai, kembali ke 80% bila setelan itu nyala.
     */
    static void setRecordingScale(Context ctx, String gamePkg, boolean recording) {
        Context app = ctx.getApplicationContext();
        if (!ShizukuHelper.ready() || !state(app).getBoolean(KEY_PERF_ACTIVE, false)) return;
        StringBuilder sb = new StringBuilder();
        SharedPreferences pr = Prefs.get(app);
        // Resolusi game 100% saat merekam hanya bila dipilih (preset Seimbang/Tajam).
        if (Build.VERSION.SDK_INT >= 33 && gamePkg != null && SAFE_PKG.matcher(gamePkg).matches()
                && Prefs.flag(app, "downscale") && pr.getBoolean(RecordService.PREF_FULLSCALE, false)) {
            sb.append("cmd game set --mode performance --downscale ").append(recording ? "1.0" : "0.8")
                    .append(' ').append(gamePkg).append('\n');
        }
        // Layar dikunci 60 Hz saat merekam: tenaga GPU tersisa untuk encoder, FPS game lebih stabil.
        if (state(app).getBoolean(KEY_RR_CHANGED, false) && pr.getBoolean(RecordService.PREF_LOWHZ, true)) {
            float max = maxRefreshRate(app);
            if (max > 61f) {
                int hz = recording ? 60 : Math.round(max);
                sb.append("settings put system min_refresh_rate ").append(hz).append('\n');
                sb.append("settings put system peak_refresh_rate ").append(hz).append('\n');
            }
        }
        if (sb.length() > 0) ShizukuHelper.exec(sb.toString());
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
        if (sp.getBoolean(KEY_SAT_ACTIVE, false)) {
            sb.append("service call SurfaceFlinger 1022 f 1.0\n");
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
