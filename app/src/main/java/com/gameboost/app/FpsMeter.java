package com.gameboost.app;

import java.util.regex.Pattern;

/**
 * Membaca FPS game dari SurfaceFlinger lewat Shizuku: cari layer milik game, ambil waktu tampil
 * tiap frame, lalu hitung jumlah frame dalam 1 detik terakhir. Mengembalikan -1 bila tidak terbaca.
 * Memblokir (shell), jadi hanya dipanggil dari thread background.
 */
final class FpsMeter {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._]+");
    private static final long INVALID = Long.MAX_VALUE;

    private FpsMeter() {}

    static int read(String pkg) {
        if (pkg == null || pkg.isEmpty() || !SAFE.matcher(pkg).matches() || !ShizukuHelper.ready()) return -1;
        String script = "L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '" + pkg + "' | grep 'SurfaceView' | head -n 1)\n"
                + "[ -z \"$L\" ] && L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '" + pkg + "' | head -n 1)\n"
                + "[ -n \"$L\" ] && dumpsys SurfaceFlinger --latency \"$L\" 2>/dev/null\n";
        String out = ShizukuHelper.exec(script);
        if (out == null) return -1;
        return parse(out);
    }

    static int parse(String out) {
        String[] lines = out.split("\n");
        long[] t = new long[lines.length];
        int n = 0;
        for (int i = 1; i < lines.length; i++) {          // baris pertama = periode refresh layar
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
}
