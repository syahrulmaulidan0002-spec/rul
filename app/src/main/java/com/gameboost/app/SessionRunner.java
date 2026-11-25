package com.gameboost.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Satu-satunya tempat yang menjalankan "persiapan mode game": tutup/bekukan aplikasi, mode performa,
 * fokus internet, dan senyap. Dipakai tombol Main (dengan animasi), widget, dan aktif otomatis.
 * Jalankan di thread background.
 */
final class SessionRunner {

    interface Listener {
        void begin(String key);
        void done(String key, String detail);
    }

    static final class Result {
        String pkg;
        boolean overlay, kill, notif, freezeOn, netOk;
        int requested, frozen;
        long freedMb;
        String method = "";

        Intent toIntent(Context c) {
            return new Intent(c, GameService.class)
                    .putExtra(MainActivity.EXTRA_GAME, pkg)
                    .putExtra(MainActivity.EXTRA_OVERLAY, overlay)
                    .putExtra(MainActivity.EXTRA_KILL, kill)
                    .putExtra(MainActivity.EXTRA_NOTIF, notif)
                    .putExtra(MainActivity.EXTRA_FREEZE, freezeOn)
                    .putExtra(MainActivity.EXTRA_NETFOCUS, netOk)
                    .putExtra(MainActivity.EXTRA_FREED, freedMb)
                    .putExtra(MainActivity.EXTRA_REQUESTED, requested)
                    .putExtra("frozen", frozen);
        }

        static Result fromIntent(Intent i) {
            Result r = new Result();
            r.pkg = i.getStringExtra(MainActivity.EXTRA_GAME);
            r.overlay = i.getBooleanExtra(MainActivity.EXTRA_OVERLAY, true);
            r.kill = i.getBooleanExtra(MainActivity.EXTRA_KILL, true);
            r.notif = i.getBooleanExtra(MainActivity.EXTRA_NOTIF, false);
            r.freezeOn = i.getBooleanExtra(MainActivity.EXTRA_FREEZE, false);
            r.netOk = i.getBooleanExtra(MainActivity.EXTRA_NETFOCUS, false);
            r.freedMb = i.getLongExtra(MainActivity.EXTRA_FREED, 0);
            r.requested = i.getIntExtra(MainActivity.EXTRA_REQUESTED, 0);
            r.frozen = i.getIntExtra("frozen", 0);
            return r;
        }
    }

    private SessionRunner() {}

    /** Jumlah langkah yang akan dijalankan (untuk progres animasi). */
    static int planned(Context c) {
        boolean shizuku = ShizukuHelper.ready();
        int n = 0;
        if (Prefs.flag(c, "kill")) n++;
        if (Prefs.flag(c, "perf") && shizuku) n++;
        if (Prefs.flag(c, "netfocus") && shizuku) n++;
        if (Prefs.flag(c, "dnd") || Prefs.flag(c, "notif")) n++;
        return n;
    }

    /** @param startService true = langsung menyalakan GameService (jalur tombol/widget). */
    static Result run(Context ctx, String pkg, Listener l, boolean startService) {
        Context app = ctx.getApplicationContext();
        Result r = new Result();
        r.pkg = pkg;
        r.kill = Prefs.flag(app, "kill");
        r.notif = Prefs.flag(app, "notif");
        r.overlay = Prefs.flag(app, "overlay");
        boolean dnd = Prefs.flag(app, "dnd");
        boolean perf = Prefs.flag(app, "perf");
        boolean net = Prefs.flag(app, "netfocus");
        boolean shizuku = ShizukuHelper.ready();
        r.freezeOn = Prefs.flag(app, "freeze") && shizuku;

        if (r.kill) {
            if (l != null) l.begin("kill");
            GameBooster.Result b = GameBooster.boost(app, pkg, r.freezeOn);
            r.requested = b.requested;
            r.frozen = b.frozen;
            r.freedMb = b.freedMb;
            r.method = b.method;
            String what = b.frozen > 0
                    ? String.format(Locale.US, "%d dibekukan", b.frozen)
                    : String.format(Locale.US, "%d ditutup", b.requested);
            if (l != null) l.done("kill", what + (b.freedMb > 0
                    ? String.format(Locale.US, " • RAM +%d MB", b.freedMb) : ""));
        }
        if (perf && shizuku) {
            if (l != null) l.begin("perf");
            GameBooster.enablePerf(app, pkg);
            if (l != null) l.done("perf", "Aktif");
        }
        if (net && shizuku) {
            if (l != null) l.begin("net");
            r.netOk = GameBooster.enableNetFocus(app, pkg);
            if (l != null) l.done("net", r.netOk ? "Fokus ke game" : "Dilewati");
        }
        if (dnd || r.notif) {
            if (l != null) l.begin("quiet");
            if (dnd) GameBooster.enableDnd(app);
            if (l != null) l.done("quiet", "Senyap");
        }

        if (startService) {
            Intent s = r.toIntent(app);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(s);
            else app.startService(s);
        }
        return r;
    }

    static void sleepQuiet(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }
}
