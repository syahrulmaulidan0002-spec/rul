package com.gameboost.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Setelah HP menyala atau aplikasi diperbarui, aktifkan lagi layanan siaga (aktif otomatis). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        GameService.syncWatch(context);
    }
}
