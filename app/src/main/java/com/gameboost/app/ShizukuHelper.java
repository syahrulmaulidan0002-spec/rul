package com.gameboost.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Pembungkus Shizuku: cek status, minta izin, dan jalankan perintah shell. */
final class ShizukuHelper {

    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String SELF_PKG = "com.gameboost.app";

    private static volatile IShellService svc;
    private static volatile CountDownLatch latch;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = IShellService.Stub.asInterface(binder);
            CountDownLatch l = latch;
            if (l != null) l.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    private ShizukuHelper() {}

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shizuku sedang menyala (sudah di-Start). */
    static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku menyala dan GameBoost sudah diberi izin. */
    static boolean granted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean ready() {
        return granted();
    }

    /** true jika Shizuku berjalan dengan hak root (dinyalakan lewat root). */
    static boolean isRoot() {
        try {
            return Shizuku.getUid() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
        }
    }

    private static Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(new ComponentName(SELF_PKG, ShellService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(3);
    }

    private static synchronized IShellService service() {
        IShellService s = svc;
        if (s != null && s.asBinder().pingBinder()) return s;
        if (!granted()) return null;
        latch = new CountDownLatch(1);
        try {
            Shizuku.bindUserService(args(), CONN);
        } catch (Throwable t) {
            return null;
        }
        try {
            latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return svc;
    }

    /**
     * Jalankan perintah shell lewat Shizuku. Memblokir sampai selesai, jadi JANGAN dipanggil
     * dari thread utama. Mengembalikan keluaran, atau null bila Shizuku tidak tersedia.
     */
    static String exec(String command) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        IShellService s = service();
        if (s == null) return null;
        try {
            return s.exec(command);
        } catch (Throwable t) {
            return null;
        }
    }
}
