package com.gameboost.app;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Selama mode game aktif, setiap notifikasi yang masuk langsung dihapus dan notifikasi
 * yang sudah ada dibersihkan. Notifikasi GameBoost sendiri tidak disentuh.
 * Butuh akses "Akses notifikasi" yang diberikan pengguna di Pengaturan.
 */
public class GameNotifService extends NotificationListenerService {

    private static volatile GameNotifService instance;

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
