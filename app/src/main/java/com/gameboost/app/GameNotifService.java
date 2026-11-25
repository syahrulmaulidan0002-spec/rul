package com.gameboost.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Selama mode game aktif, setiap notifikasi yang masuk langsung dihapus dan notifikasi
 * yang sudah ada dibersihkan. Notifikasi GameBoost sendiri tidak disentuh.
 * Butuh akses "Akses notifikasi" yang diberikan pengguna di Pengaturan.
 */
public class GameNotifService extends NotificationListenerService {

    private static volatile GameNotifService instance;

    // Hanya hitungan per aplikasi; isi pesan tidak pernah disimpan.
    private static final Map<String, Integer> MISSED = new HashMap<>();

    static synchronized void resetMissed() {
        MISSED.clear();
    }

    static synchronized Map<String, Integer> takeMissed() {
        Map<String, Integer> copy = new HashMap<>(MISSED);
        MISSED.clear();
        return copy;
    }

    private static synchronized void countMissed(String pkg) {
        Integer n = MISSED.get(pkg);
        MISSED.put(pkg, n == null ? 1 : n + 1);
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        if (shouldBlock()) clearAll();
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !shouldBlock()) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        boolean ongoing = n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean summary = n != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (!ongoing && !summary) countMissed(sbn.getPackageName());
        try {
            cancelNotification(sbn.getKey());
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldBlock() {
        return GameService.running && GameService.blockNotifs;
    }

    /** Hapus semua notifikasi yang sedang tampil (selain milik GameBoost). */
    static void clearAll() {
        GameNotifService s = instance;
        if (s == null) return;
        try {
            StatusBarNotification[] all = s.getActiveNotifications();
            if (all == null) return;
            for (StatusBarNotification n : all) {
                if (!s.getPackageName().equals(n.getPackageName())) {
                    s.cancelNotification(n.getKey());
                }
            }
        } catch (Exception ignored) {
        }
    }

    static boolean isEnabled(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String me = new ComponentName(c, GameNotifService.class).flattenToString();
        return flat.contains(me);
    }
}
