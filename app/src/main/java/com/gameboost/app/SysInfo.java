package com.gameboost.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import java.util.Locale;

/** Pembaca info sistem: RAM, baterai/suhu, dan saran setelan grafis. */
final class SysInfo {

    private static final long MB = 1024L * 1024L;

    private SysInfo() {}

    static final class Ram {
        long totalMb;
        long availMb;
        long usedMb;
        int usedPct;
    }

    static final class Batt {
        int level;
        float tempC;
        boolean charging;
    }

    static Ram ram(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        Ram r = new Ram();
        r.totalMb = mi.totalMem / MB;
        r.availMb = mi.availMem / MB;
        r.usedMb = Math.max(0, r.totalMb - r.availMb);
        r.usedPct = r.totalMb > 0 ? (int) Math.round(100.0 * r.usedMb / r.totalMb) : 0;
        return r;
    }

    static Batt battery(Context c) {
        Batt b = new Batt();
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return b;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        b.level = level >= 0 && scale > 0 ? Math.round(100f * level / scale) : 0;
        b.tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        b.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return b;
    }

    /** Nama chipset, mis. "Snapdragon SM6225". */
    static String chipset() {
        String man = "";
        String model = "";
        if (Build.VERSION.SDK_INT >= 31) {
            man = Build.SOC_MANUFACTURER == null ? "" : Build.SOC_MANUFACTURER;
            model = Build.SOC_MODEL == null ? "" : Build.SOC_MODEL;
        }
        if (model.isEmpty() || "unknown".equalsIgnoreCase(model)) {
            model = Build.HARDWARE == null ? "" : Build.HARDWARE;
        }
        String lm = man.toLowerCase(Locale.US);
        String lh = (Build.HARDWARE == null ? "" : Build.HARDWARE).toLowerCase(Locale.US);
        if (lm.contains("qualcomm") || lh.equals("qcom")) {
            return ("Snapdragon " + (model.equalsIgnoreCase("qcom") ? "" : model)).trim();
        }
        return (man + " " + model).trim();
    }

    /** Saran setelan grafis (untuk Mobile Legends dan game sejenis) berdasarkan RAM. */
    static String advice(Context c) {
        Ram r = ram(c);
        int gb = Math.max(1, Math.round(r.totalMb / 1024f));
        String head = String.format(Locale.US, "RAM %d GB  •  Android %s", gb, Build.VERSION.RELEASE);
        String tips;
        if (r.totalMb < 2500) {
            tips = "Grafis: Smooth  •  Frame rate: Normal/Tinggi\nMode HD: mati  •  Bayangan: mati  •  Efek: rendah";
        } else if (r.totalMb < 4500) {
            tips = "Grafis: Smooth  •  Frame rate: Tinggi\nMode HD: mati  •  Bayangan: mati";
        } else if (r.totalMb < 7500) {
            tips = "Grafis: HD  •  Frame rate: Tinggi\nBayangan: opsional";
        } else {
            tips = "Grafis: HD/Ultra  •  Frame rate: Tinggi/Ultra";
        }
        String chip = chipset();
        return (chip.isEmpty() ? "" : "Chipset: " + chip + "\n") + head + "\n" + tips;
    }

    static int color(Context c, int resId) {
        return Build.VERSION.SDK_INT >= 23
                ? c.getColor(resId)
                : c.getResources().getColor(resId);
    }
}
