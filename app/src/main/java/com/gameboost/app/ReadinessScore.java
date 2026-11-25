package com.gameboost.app;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;

import java.util.Locale;

/** Skor kesiapan HP sebelum main (0-100) dari RAM, suhu, baterai, dan sisa penyimpanan. */
final class ReadinessScore {

    static final class Result {
        int score;
        String tip;
        int colorRes;
    }

    private ReadinessScore() {}

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    static Result compute(Context c) {
        SysInfo.Ram r = SysInfo.ram(c);
        SysInfo.Batt b = SysInfo.battery(c);

        double freeGb = 10;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            freeGb = fs.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
        } catch (Exception ignored) {
        }

        int ramFree = 100 - r.usedPct;
        double ram = clamp01((ramFree - 10) / 40.0);                    // sisa RAM 10% -> 0, 50% -> 1
        double temp = clamp01((44.0 - b.tempC) / 10.0);                 // 44C -> 0, 34C -> 1
        double batt = b.charging ? 1.0 : clamp01((b.level - 15) / 35.0); // 15% -> 0, 50% -> 1
        double stor = clamp01((freeGb - 1.0) / 9.0);                    // 1 GB -> 0, 10 GB -> 1

        Result res = new Result();
        res.score = (int) Math.round(40 * ram + 25 * temp + 20 * batt + 15 * stor);

        double worst = Math.min(Math.min(ram, temp), Math.min(batt, stor));
        if (worst >= 0.85) {
            res.tip = "HP siap, selamat bermain";
        } else if (worst == temp) {
            res.tip = String.format(Locale.US, "Suhu %.1f°C, biarkan dingin 5 menit", b.tempC);
        } else if (worst == batt) {
            res.tip = "Baterai " + b.level + "%, colok charger dulu";
        } else if (worst == stor) {
            res.tip = String.format(Locale.US, "Penyimpanan tersisa %.1f GB, hapus file yang tidak perlu", freeGb);
        } else {
            res.tip = "RAM terpakai " + r.usedPct + "%, GameBoost akan melegakannya saat mulai";
        }
        res.colorRes = res.score >= 80 ? R.color.ok : (res.score >= 55 ? R.color.warn : R.color.danger);
        return res;
    }
}
