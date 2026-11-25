package com.gameboost.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Pembaca statistik langsung untuk grafik panel mengambang: CPU, GPU, dan kecepatan internet.
 *
 * Android 8+ melarang aplikasi biasa membaca /proc/stat dan data GPU. Jika Shizuku aktif, data itu
 * dibaca lewat Shizuku. Jika tidak: CPU diperkirakan dari frekuensi (bila terbaca), GPU tampil "—".
 * Satu instance menyimpan sampel sebelumnya, jadi pakai satu instance per sesi dan JANGAN dipanggil
 * dari thread utama.
 */
final class SysStats {

    static final class Snap {
        int cpu = -1;            // persen, -1 = tidak tersedia
        int gpu = -1;
        boolean gpuByFreq;       // true = GPU diperkirakan dari frekuensi
        String gpuModel;         // mis. "Adreno610" (Snapdragon), bila terbaca
        boolean cpuByFreq;       // true = hanya perkiraan dari frekuensi
        long rxBps = -1;         // byte per detik, -1 = belum ada
        long txBps = -1;
    }

    private static final String[] GPU_FILES = {
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/devices/platform/*.mali/utilization",
            "/sys/devices/platform/*mali*/utilization",
            "/sys/devices/platform/soc/*.mali/utilization"
    };

    private static final String SCRIPT = buildScript();

    private long prevTotal = -1;
    private long prevIdle = -1;
    private long prevRx = -1;
    private long prevTx = -1;
    private long prevT = 0;

    private static String buildScript() {
        StringBuilder sb = new StringBuilder("head -n 1 /proc/stat\nfor f in");
        for (String f : GPU_FILES) sb.append(' ').append(f);
        sb.append("; do if [ -r \"$f\" ]; then echo \"GPUFILE:$f\"; head -c 200 \"$f\"; echo; break; fi; done\n");
        // Snapdragon (Adreno): frekuensi dan nama GPU sebagai cadangan bila beban tidak terbaca
        sb.append("echo GPUCLK\ncat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null\n");
        sb.append("cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null\n");
        sb.append("echo GPUMODEL\ncat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null\necho END\n");
        return sb.toString();
    }

    Snap sample(Context ctx) {
        Snap s = new Snap();
        String cpuLine = null;
        String gpuFile = null;
        String gpuText = null;
        String model = null;
        long clkCur = 0;
        long clkMax = 0;

        if (ShizukuHelper.ready()) {
            String out = ShizukuHelper.exec(SCRIPT);
            if (out != null) {
                String[] lines = out.split("\n");
                String section = null;
                int clkIdx = 0;
                for (int i = 0; i < lines.length; i++) {
                    String l = lines[i];
                    if (l.equals("GPUCLK")) {
                        section = "clk";
                        clkIdx = 0;
                    } else if (l.equals("GPUMODEL")) {
                        section = "model";
                    } else if (l.equals("END")) {
                        section = null;
                    } else if ("clk".equals(section)) {
                        try {
                            long v = Long.parseLong(l.trim());
                            if (clkIdx == 0) clkCur = v; else clkMax = v;
                            clkIdx++;
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("model".equals(section)) {
                        if (!l.trim().isEmpty()) model = l.trim();
                    } else if (l.startsWith("cpu ") && cpuLine == null) {
                        cpuLine = l;
                    } else if (l.startsWith("GPUFILE:")) {
                        gpuFile = l.substring(8);
                        gpuText = i + 1 < lines.length ? lines[i + 1] : null;
                    }
                }
            }
        }

        if (cpuLine == null) cpuLine = firstLine("/proc/stat");
        if (gpuFile == null) {
            for (String f : GPU_FILES) {
                if (f.indexOf('*') >= 0) continue;     // karakter bintang hanya berlaku lewat shell
                String t = firstLine(f);
                if (t != null) {
                    gpuFile = f;
                    gpuText = t;
                    break;
                }
            }
        }

        s.cpu = cpuFromStat(cpuLine);
        if (cpuLine == null) {
            int f = cpuFromFreq();
            if (f >= 0) {
                s.cpu = f;
                s.cpuByFreq = true;
            }
        }
        s.gpu = gpuPercent(gpuFile, gpuText);
        s.gpuModel = model;
        if (s.gpu < 0 && clkCur > 0 && clkMax > 0) {
            s.gpu = (int) Math.max(0, Math.min(100, Math.round(100.0 * clkCur / clkMax)));
            s.gpuByFreq = true;
        }

        long now = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (rx >= 0 && tx >= 0 && prevRx >= 0 && now > prevT) {
            long dt = now - prevT;
            s.rxBps = Math.max(0, (rx - prevRx) * 1000L / dt);
            s.txBps = Math.max(0, (tx - prevTx) * 1000L / dt);
        }
        prevRx = rx;
        prevTx = tx;
        prevT = now;
        return s;
    }

    // ---------------------------------------------------------------- CPU

    private int cpuFromStat(String line) {
        if (line == null || !line.startsWith("cpu")) return -1;
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) return -1;
        try {
            long total = 0;
            long idle = 0;
            for (int i = 1; i < Math.min(p.length, 9); i++) {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;        // idle + iowait
            }
            int pct = -1;
            if (prevTotal >= 0 && total > prevTotal) {
                long dt = total - prevTotal;
                long di = idle - prevIdle;
                pct = (int) Math.max(0, Math.min(100, 100L * (dt - di) / dt));
            }
            prevTotal = total;
            prevIdle = idle;
            return pct;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Perkiraan beban CPU dari rata-rata frekuensi saat ini dibanding frekuensi maksimum. */
    private int cpuFromFreq() {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 16; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String cur = firstLine(base + "scaling_cur_freq");
            String max = firstLine(base + "cpuinfo_max_freq");
            if (cur == null || max == null) continue;
            try {
                double c = Double.parseDouble(cur.trim());
                double m = Double.parseDouble(max.trim());
                if (m > 0) {
                    sum += Math.min(1.0, c / m);
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return n == 0 ? -1 : (int) Math.round(100.0 * sum / n);
    }

    // ---------------------------------------------------------------- GPU

    private int gpuPercent(String file, String text) {
        if (file == null || text == null) return -1;
        int[] nums = ints(text);
        if (nums.length == 0) return -1;
        if (file.endsWith("gpubusy")) {
            // format lama: "<sibuk> <total>"
            if (nums.length >= 2 && nums[1] > 0) {
                return Math.max(0, Math.min(100, (int) (100L * nums[0] / nums[1])));
            }
            return -1;
        }
        return Math.max(0, Math.min(100, nums[0]));
    }

    private static int[] ints(String text) {
        String[] p = text.trim().split("[^0-9]+");
        int[] tmp = new int[p.length];
        int n = 0;
        for (String x : p) {
            if (x.isEmpty()) continue;
            try {
                tmp[n++] = Integer.parseInt(x);
            } catch (NumberFormatException ignored) {
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    // ---------------------------------------------------------------- umum

    private static String firstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path), 256)) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /** "Wi-Fi", "Seluler", atau "Offline". Aman dipanggil dari thread utama. */
    static String networkType(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            if (nc == null) return "Offline";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Seluler";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Kabel";
            return "Online";
        } catch (Exception e) {
            return "";
        }
    }

    static String speed(long bps) {
        if (bps < 0) return "—";
        double kb = bps / 1024.0;
        if (kb < 1000) return String.format(Locale.US, "%.0f KB/s", kb);
        return String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
    }
}
