package com.gameboost.app;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;

/** Membuka game, opsional di jendela kecil (freeform, eksperimental). */
final class GameLauncher {

    private GameLauncher() {}

    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle opts = null;
        if (Prefs.flag(c, "freeform") && Build.VERSION.SDK_INT >= 24) {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            Rect r = new Rect(w / 20, h / 8, w - w / 20, h / 8 + (int) (h * 0.55f));
            opts = ActivityOptions.makeBasic().setLaunchBounds(r).toBundle();
        }
        try {
            c.startActivity(i, opts);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
