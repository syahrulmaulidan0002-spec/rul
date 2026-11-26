package com.gameboost.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Screenshot dan rekam layar lewat Shizuku (screencap / screenrecord). Jangan dipanggil dari thread utama. */
final class ScreenCapture {

    static final String PIC_DIR = "/sdcard/Pictures/GameBoost";
    static final String VID_DIR = "/sdcard/Movies/GameBoost";
    private static final String STOP_FILE = "/data/local/tmp/gb_stop";

    private ScreenCapture() {}

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static String scan(String path) {
        return "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + path
                + " >/dev/null 2>&1; ";
    }

    /** Ambil screenshot. Mengembalikan path file, atau null bila gagal. */
    static String screenshot() {
        String path = PIC_DIR + "/GB_" + stamp() + ".png";
        String out = ShizukuHelper.exec("mkdir -p " + PIC_DIR + "; screencap -p " + path
                + " && echo GB_OK; " + scan(path));
        return out != null && out.contains("GB_OK") ? path : null;
    }

    /**
     * Mulai rekam layar. Ukuran dibatasi (sisi panjang maks 1280) dan bitrate rendah supaya HP
     * lama tidak berat. Android membatasi satu rekaman 180 detik, jadi direkam bersambung per file.
     */
    static boolean startRecord(int screenW, int screenH) {
        float k = Math.min(1f, 1280f / Math.max(screenW, screenH));
        int w = Math.max(2, Math.round(screenW * k) & ~1);
        int h = Math.max(2, Math.round(screenH * k) & ~1);
        String loop = "while [ ! -e " + STOP_FILE + " ]; do screenrecord --bit-rate 6000000 --size "
                + w + "x" + h + " --time-limit 180 " + VID_DIR + "/GB_$(date +%Y%m%d_%H%M%S).mp4 || break; done";
        String out = ShizukuHelper.exec("rm -f " + STOP_FILE + "; mkdir -p " + VID_DIR
                + "; (sh -c '" + loop + "' >/dev/null 2>&1 &); sleep 1; "
                + "pidof screenrecord >/dev/null && echo GB_OK");
        return out != null && out.contains("GB_OK");
    }

    /** Hentikan rekaman dan daftarkan hasilnya ke galeri. Mengembalikan nama file terakhir. */
    static String stopRecord() {
        String out = ShizukuHelper.exec("touch " + STOP_FILE + "; pkill -2 screenrecord || killall -2 screenrecord; "
                + "sleep 2; for f in $(ls -t " + VID_DIR + " | head -3); do am broadcast "
                + "-a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + VID_DIR
                + "/$f >/dev/null 2>&1; done; ls -t " + VID_DIR + " | head -1; rm -f " + STOP_FILE);
        if (out == null) return null;
        String name = out.trim();
        return name.isEmpty() ? null : name;
    }
}
