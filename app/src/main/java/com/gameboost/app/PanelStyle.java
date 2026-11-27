package com.gameboost.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Gaya "liquid glass" panel mengambang, dipakai panel asli dan simulasi di Pengaturan. */
final class PanelStyle {

    static final String KEY = "glass";
    static final int DEFAULT = 45;          // 0 = paling bening, 100 = paling pekat

    private PanelStyle() {}

    static int opacity(Context c) {
        return Math.max(0, Math.min(100, Prefs.get(c).getInt(KEY, DEFAULT)));
    }

    private static Drawable tile(Context c, int o) {
        float a = o / 100f;
        return new GlassDrawable(c, 18, 0xFFFFFF, 0.05f + 0.13f * a, 0.8f);
    }

    private static Drawable pressable(Context c, Drawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(18 * c.getResources().getDisplayMetrics().density);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask);
    }

    /** Tombol rekam: kaca kemerahan saat merekam, kaca biasa saat diam. */
    static Drawable recButton(Context c, boolean recording, int o) {
        if (!recording) return pressable(c, tile(c, o));
        return pressable(c, new GlassDrawable(c, 18, 0xFF453A, 0.32f, 1f));
    }

    /** Pasang kaca ke kartu, kotak grafik, pil, dan tombol. Aman dipanggil berulang. */
    static void apply(View root, int o) {
        Context c = root.getContext();
        float a = o / 100f;
        View card = root.findViewById(R.id.card);
        if (card != null) {
            int padL = card.getPaddingLeft(), padT = card.getPaddingTop();
            int padR = card.getPaddingRight(), padB = card.getPaddingBottom();
            card.setBackground(new GlassDrawable(c, 28, 0x0B0E18, 0.07f + 0.78f * a, 1f));
            card.setPadding(padL, padT, padR, padB);
        }
        int[] rows = {R.id.gridRow1, R.id.gridRow2};
        for (int id : rows) {
            ViewGroup row = root.findViewById(id);
            if (row == null) continue;
            for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setBackground(tile(c, o));
        }
        int[] pills = {R.id.pillNet, R.id.pillFps, R.id.pillTemp};
        for (int id : pills) {
            View v = root.findViewById(id);
            if (v != null) v.setBackground(tile(c, o));
        }
        int[] btns = {R.id.btnShot, R.id.btnClean, R.id.btnStop};
        for (int id : btns) {
            View v = root.findViewById(id);
            if (v != null) v.setBackground(pressable(c, tile(c, o)));
        }
        View rec = root.findViewById(R.id.btnRec);
        if (rec != null && !Boolean.TRUE.equals(rec.getTag())) rec.setBackground(recButton(c, false, o));
    }

    /** Buat satu kotak grafik di baris tertentu; mengembalikan tampilannya. */
    static View addCell(LinearLayout row, String label, int color, float fixedMax, float floor) {
        View v = LayoutInflater.from(row.getContext()).inflate(R.layout.cell_metric, row, false);
        ((TextView) v.findViewById(R.id.cellLabel)).setText(label);
        SparkView sp = v.findViewById(R.id.cellSpark);
        sp.setColor(color);
        sp.setFixedMax(fixedMax, floor);
        row.addView(v);
        return v;
    }
}
