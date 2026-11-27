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

    static final String KEY = "glass2";   // kunci baru: semua pengguna mulai dari nilai bawaan yang lebih pekat
    static final int DEFAULT = 72;          // 0 = paling bening, 100 = paling pekat

    private PanelStyle() {}

    static int opacity(Context c) {
        return Math.max(0, Math.min(100, Prefs.get(c).getInt(KEY, DEFAULT)));
    }

    private static Drawable tile(Context c, int o) {
        return tile(c, o, 18);
    }

    private static Drawable tile(Context c, int o, float r) {
        float a = o / 100f;
        return new GlassDrawable(c, r, 0xFFFFFF, 0.05f + 0.13f * a, 0.8f);
    }

    private static Drawable pressable(Context c, Drawable content) {
        return pressable(c, content, 18);
    }

    private static Drawable pressable(Context c, Drawable content, float r) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(r * c.getResources().getDisplayMetrics().density);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask);
    }

    /** Tombol rekam: kaca kemerahan saat merekam, kaca biasa saat diam. */
    static Drawable recButton(Context c, boolean recording, int o) {
        if (!recording) return pressable(c, new GlassDrawable(c, 22, 0xFF453A, 0.16f + 0.1f * (o / 100f), 1f), 22);
        return pressable(c, new GlassDrawable(c, 22, 0xFF453A, 0.42f, 1f), 22);
    }

    /** Pasang kaca ke kartu, kotak grafik, pil, dan tombol. Aman dipanggil berulang. */
    static void apply(View root, int o) {
        Context c = root.getContext();
        float a = o / 100f;
        View card = root.findViewById(R.id.card);
        if (card != null) {
            int padL = card.getPaddingLeft(), padT = card.getPaddingTop();
            int padR = card.getPaddingRight(), padB = card.getPaddingBottom();
            card.setBackground(new GlassDrawable(c, 30, 0x0B0E18, 0.34f + 0.54f * a, 1f));
            card.setPadding(padL, padT, padR, padB);
        }
        int[] rows = {R.id.gridRow1, R.id.gridRow2};
        for (int id : rows) {
            ViewGroup row = root.findViewById(id);
            if (row == null) continue;
            for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setBackground(tile(c, o));
        }
        View hero = root.findViewById(R.id.heroCard);
        if (hero != null) hero.setBackground(new GlassDrawable(c, 22, 0x30D158, 0.10f + 0.10f * a, 0.9f));
        View net = root.findViewById(R.id.pillNet);
        if (net != null) net.setBackground(tile(c, o, 16));
        int[] btns = {R.id.btnShot, R.id.btnClean, R.id.btnStop};
        for (int id : btns) {
            View v = root.findViewById(id);
            if (v != null) v.setBackground(pressable(c, tile(c, o, 22), 22));
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
