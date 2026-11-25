package com.gameboost.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.widget.RemoteViews;

/** Widget layar utama: menampilkan game terpilih dan membuka animasi masuk game saat diketuk. */
public class GameWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        update(c);
    }

    /** Perbarui semua widget (dipanggil saat game terpilih berubah). */
    static void update(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, GameWidget.class));
            if (ids == null || ids.length == 0) return;

            String pkg = Prefs.get(c).getString("game_pkg", null);
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_game);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

            Intent click;
            String name = null;
            Bitmap icon = null;
            if (pkg != null) {
                try {
                    name = String.valueOf(c.getPackageManager().getApplicationLabel(
                            c.getPackageManager().getApplicationInfo(pkg, 0)));
                    icon = toBitmap(c.getPackageManager().getApplicationIcon(pkg), 96);
                } catch (Exception e) {
                    pkg = null;
                }
            }
            if (pkg != null) {
                click = new Intent(c, LaunchActivity.class).putExtra(LaunchActivity.EXTRA_PKG, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                rv.setTextViewText(R.id.wName, name);
                rv.setTextViewText(R.id.wSub, "Ketuk untuk main");
                if (icon != null) rv.setImageViewBitmap(R.id.wIcon, icon);
            } else {
                click = new Intent(c, MainActivity.class);
                rv.setTextViewText(R.id.wName, "Pilih game");
                rv.setTextViewText(R.id.wSub, "Buka GameBoost");
                rv.setImageViewResource(R.id.wIcon, R.drawable.ic_gamepad);
            }
            rv.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(c, 0, click, piFlags));
            m.updateAppWidget(ids, rv);
        } catch (Exception ignored) {
        }
    }

    private static Bitmap toBitmap(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(cv);
        return b;
    }
}
