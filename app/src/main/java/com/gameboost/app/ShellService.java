package com.gameboost.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Berjalan di proses terpisah milik Shizuku (uid shell 2000, atau root 0 bila Shizuku dinyalakan
 * lewat root). Hanya menjalankan perintah yang dikirim GameBoost.
 */
public class ShellService extends IShellService.Stub {

    private static final int MAX_OUT = 16 * 1024;

    public ShellService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < MAX_OUT) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
