package com.gameboost.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Screenshot lewat Shizuku (screencap). Rekam layar ada di RecordService. Jangan dipanggil dari thread utama. */
final class ScreenCapture {

    static final String PIC_DIR = "/sdcard/Pictures/GameBoost";

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
}
