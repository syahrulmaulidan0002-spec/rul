package com.gameboost.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.Locale;

/**
 * Bantuan untuk pengaturan khusus merek (HyperOS/MIUI, ColorOS, Funtouch, One UI, dll.).
 * Merek-merek ini punya "Autostart" dan hemat baterai sendiri yang bisa mematikan GameBoost.
 */
final class VendorHelper {

    private VendorHelper() {}

    private static String maker() {
        return (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
    }

    static boolean isXiaomi() {
        String m = maker();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private static boolean has(String s) {
        return maker().contains(s);
    }

    static String title() {
        if (isXiaomi()) return "Autostart & latar belakang (HyperOS)";
        if (has("oppo") || has("realme") || has("oneplus")) return "Izin latar belakang (ColorOS/OxygenOS)";
        if (has("vivo") || has("iqoo")) return "Izin latar belakang (Funtouch/OriginOS)";
        if (has("samsung")) return "Batasan baterai (One UI)";
        if (has("huawei") || has("honor")) return "Luncur otomatis (EMUI/MagicOS)";
        return "Izin latar belakang HP";
    }

    static String hint() {
        if (isXiaomi()) {
            return "HyperOS/MIUI: nyalakan Autostart untuk GameBoost, lalu di Info Aplikasi > Hemat baterai "
                    + "pilih \"Tanpa batasan\". Di Izin lainnya, aktifkan \"Tampilkan pop-up saat berjalan di "
                    + "latar belakang\" agar jendela mengambang tidak diblokir.";
        }
        if (has("oppo") || has("realme") || has("oneplus")) {
            return "Nyalakan \"Luncur otomatis\" dan izinkan aktivitas latar belakang untuk GameBoost.";
        }
        if (has("vivo") || has("iqoo")) {
            return "Izinkan GameBoost berjalan di latar belakang dan tampil di atas aplikasi lain.";
        }
        if (has("samsung")) {
            return "Di Baterai, masukkan GameBoost ke \"Aplikasi tak pernah tidur\".";
        }
        return "Jika jendela mengambang menghilang, izinkan GameBoost berjalan di latar belakang "
                + "di pengaturan baterai HP.";
    }

    /** Buka halaman Autostart/latar belakang bawaan merek; jika tidak ada, buka Info Aplikasi. */
    static void open(Context c) {
        String[][] list;
        if (isXiaomi()) {
            list = new String[][]{
                    {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                    {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"}};
        } else if (has("oppo") || has("realme") || has("oneplus")) {
            list = new String[][]{
                    {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                    {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                    {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"}};
        } else if (has("vivo") || has("iqoo")) {
            list = new String[][]{
                    {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                    {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"}};
        } else if (has("huawei") || has("honor")) {
            list = new String[][]{
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                    {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"}};
        } else if (has("samsung")) {
            list = new String[][]{
                    {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"}};
        } else {
            list = new String[0][];
        }

        for (String[] cn : list) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(cn[0], cn[1]));
                i.putExtra("package_name", c.getPackageName());
                i.putExtra("package_label", c.getString(R.string.app_name));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
