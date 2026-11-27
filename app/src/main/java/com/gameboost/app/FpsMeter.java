package com.gameboost.app;

import java.util.regex.Pattern;

/**
 * Membaca FPS game lewat Shizuku. Cara utama: waktu tampil tiap frame dari SurfaceFlinger (semua
 * layer milik game dicoba, diambil yang paling aktif). Cadangan: hitungan frame dari gfxinfo.
 * Memblokir (shell), jadi hanya dipanggil dari thread background.
 * Nilai negatif = sebab gagal: lihat ERR_*.
 */
final class FpsMeter {

    static final int ERR_SHIZUKU = -1;      // Shizuku belum aktif
    static final int ERR_NOGAME = -2;       // belum ada game yang dikenali
    static final int ERR_NOLAYER = -3;      // layer game tidak ditemukan
    static final int ERR_NODATA = -4;       // layer ada tapi data frame kosong

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._]+");
    private static final long INVALID = Long.MAX_VALUE;
    private static long prevFrames = -1, prevAt = 0;
    private static String prevPkg = "";

    private FpsMeter() {}

    static int read(String pkg) {
        if (!ShizukuHelper.ready()) return ERR_SHIZUKU;
        if (pkg == null || pkg.isEmpty() || !SAFE.matcher(pkg).matches()) return ERR_NOGAME;
        String script = "LIST=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '" + pkg
                + "' | grep -v -e 'Background for' -e 'Bounds for' | head -n 8)\n"
                + "if [ -z \"$LIST\" ]; then echo GB_NOLAYER; else\n"
                + "echo \"$LIST\" | while IFS= read -r L; do\n"
                + "  if [ -n \"$L\" ]; then echo \"GB_LAYER $L\"; dumpsys SurfaceFlinger --latency \"$L\" 2>/dev/null; fi\n"
                + "done; fi\n";
        String out = ShizukuHelper.exec(script);
        if (out == null) return ERR_SHIZUKU;
        boolean noLayer = out.contains("GB_NOLAYER");
        int best = parseBest(out);
        if (best > 0) return best;
        int alt = gfxinfo(pkg);
        if (alt > 0) return alt;
        return noLayer ? ERR_NOLAYER : ERR_NODATA;
    }

    /** Dari beberapa layer, ambil FPS terbesar (layer isi game yang paling aktif). */
    static int parseBest(String out) {
        int best = -1;
        String[] parts = out.split("GB_LAYER ");
        for (int p = 1; p < parts.length; p++) {
            int f = parse(parts[p]);
            if (f > best) best = f;
        }
        return best;
    }

    static int parse(String section) {
        String[] lines = section.split("\n");
        long[] t = new long[lines.length];
        int n = 0;
        for (int i = 1; i < lines.length; i++) {          // baris pertama = nama layer, kedua = periode layar
            String[] c = lines[i].trim().split("\\s+");
            if (c.length < 3) continue;
            try {
                long v = Long.parseLong(c[1]);
                if (v > 0 && v != INVALID) t[n++] = v;
            } catch (NumberFormatException ignored) {
            }
        }
        if (n < 3) return -1;
        long last = 0;
        for (int i = 0; i < n; i++) last = Math.max(last, t[i]);
        long first = last;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (t[i] > last - 1_000_000_000L) {
                cnt++;
                first = Math.min(first, t[i]);
            }
        }
        if (cnt < 2 || last <= first) return -1;
        float fps = (cnt - 1) * 1_000_000_000f / (last - first);
        return Math.max(1, Math.min(240, Math.round(fps)));
    }

    /** Cadangan untuk game yang digambar lewat HWUI: selisih "Total frames rendered" per detik. */
    private static int gfxinfo(String pkg) {
        String out = ShizukuHelper.exec("dumpsys gfxinfo " + pkg + " 2>/dev/null | grep 'Total frames rendered'");
        if (out == null) return -1;
        long total = -1;
        try {
            int i = out.indexOf(':');
            if (i >= 0) total = Long.parseLong(out.substring(i + 1).trim().split("\\s+")[0]);
        } catch (Exception ignored) {
        }
        if (total < 0) return -1;
        long now = System.currentTimeMillis();
        int fps = -1;
        if (pkg.equals(prevPkg) && prevFrames >= 0 && now > prevAt && total >= prevFrames) {
            fps = Math.round((total - prevFrames) * 1000f / (now - prevAt));
            if (fps <= 0) fps = -1;
        }
        prevPkg = pkg;
        prevFrames = total;
        prevAt = now;
        return Math.min(fps, 240);
    }
}
