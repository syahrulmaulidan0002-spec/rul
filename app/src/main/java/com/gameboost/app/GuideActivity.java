package com.gameboost.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panduan Shizuku berupa daftar status yang dipantau terus-menerus:
 * hijau = sudah beres, merah = belum (disertai petunjuk singkat dan tombol).
 */
public class GuideActivity extends Activity {

    private interface Check { boolean ok(); }

    private static final int REQ_SHIZUKU = 100;
    private static final int POLL_MS = 1000;

    private static final class Row {
        final String title, hintRed, hintGreen, action;
        final Runnable run;
        final Check check;
        final Check visible;       // null = selalu tampil
        final boolean required;    // ikut menentukan status "siap"
        View root, divider, dot;
        ImageView icon;
        TextView hint, btn;
        boolean ok;

        Row(String title, String hintRed, String hintGreen, String action, Runnable run,
            Check check, Check visible, boolean required) {
            this.title = title;
            this.hintRed = hintRed;
            this.hintGreen = hintGreen;
            this.action = action;
            this.run = run;
            this.check = check;
            this.visible = visible;
            this.required = required;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean testBusy = new AtomicBoolean(false);
    private volatile boolean testOk = false;
    private int ticks = 0;

    private View sumDot;
    private ImageView sumIcon;
    private TextView sumTitle, sumSub;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            runTestIfNeeded();
            refresh();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        if (Build.VERSION.SDK_INT >= 35) {
            findViewById(R.id.rootGuide).setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        findViewById(R.id.gBack).setOnClickListener(v -> finish());
        sumDot = findViewById(R.id.gSumDot);
        sumIcon = findViewById(R.id.gSumIcon);
        sumTitle = findViewById(R.id.gSumTitle);
        sumSub = findViewById(R.id.gSumSub);

        defineRows();
        LinearLayout box = findViewById(R.id.gRows);
        for (Row r : rows) {
            buildRow(box, r);
        }
        refresh();
        Motion.pressAll(findViewById(R.id.gBack));
        Motion.stagger(box, 40);
    }


    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.pop_in, R.anim.pop_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticks = 0;
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    // ---------------------------------------------------------------- daftar status

    private void defineRows() {
        rows.add(new Row("Shizuku terpasang", "Pasang dari Play Store.", "Terpasang",
                "Pasang", this::openPlayStore, () -> ShizukuHelper.installed(this), null, true));

        rows.add(new Row("Opsi pengembang", "Ketuk \"Nomor build\" 7 kali.\nHyperOS: \"Versi OS\" 7 kali.",
                "Aktif", "Buka", () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
                () -> Settings.Global.getInt(getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1,
                () -> !ShizukuHelper.running(), false));

        rows.add(new Row("Debugging nirkabel", "Nyalakan di Opsi pengembang. Wi-Fi harus tersambung.",
                "Aktif", "Buka", () -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
                () -> Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1,
                () -> !ShizukuHelper.running(), false));

        rows.add(new Row("Shizuku menyala",
                "Buka Shizuku, lalu:\n• Tanpa root: Pairing → Start\n• Root: Start (root)",
                "Menyala", "Buka Shizuku", this::openShizuku, ShizukuHelper::running, null, true));

        rows.add(new Row("Izin GameBoost", "Ketuk Beri izin, pilih \"Izinkan selalu\".",
                "Diizinkan", "Beri izin", () -> {
                    if (!ShizukuHelper.running()) {
                        toast("Nyalakan Shizuku dulu");
                    } else {
                        ShizukuHelper.requestPermission(REQ_SHIZUKU);
                    }
                }, ShizukuHelper::granted, null, true));

        rows.add(new Row("Mode root (opsional)", "Tanpa root. Tetap bisa membekukan lewat ADB.",
                "Root aktif", null, null, () -> ShizukuHelper.running() && ShizukuHelper.isRoot(),
                null, false));

        rows.add(new Row("Bisa membekukan aplikasi", "Perintah belum bisa dijalankan.",
                "Perintah berhasil dijalankan", null, null, () -> testOk, null, true));
    }

    private void buildRow(LinearLayout parent, Row r) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        View div = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        dlp.setMarginStart(dp(58));
        div.setLayoutParams(dlp);
        div.setBackgroundColor(SysInfo.color(this, R.color.stroke));
        div.setVisibility(parent.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        wrap.addView(div);
        r.divider = div;

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), dp(12), dp(14), dp(12));

        FrameLayout dot = new FrameLayout(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        dot.setBackgroundResource(R.drawable.dot_step);
        ImageView ic = new ImageView(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER);
        ic.setLayoutParams(ilp);
        ic.setColorFilter(0xFFFFFFFF);
        ic.setContentDescription(null);
        dot.addView(ic);
        line.addView(dot);
        r.dot = dot;
        r.icon = ic;

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(14));
        tlp.setMarginEnd(dp(8));
        texts.setLayoutParams(tlp);

        TextView title = new TextView(this);
        title.setText(r.title);
        title.setTextColor(SysInfo.color(this, R.color.text));
        title.setTextSize(16f);
        texts.addView(title);

        TextView hint = new TextView(this);
        hint.setTextColor(SysInfo.color(this, R.color.muted));
        hint.setTextSize(13f);
        hint.setPadding(0, dp(2), 0, 0);
        texts.addView(hint);
        r.hint = hint;
        line.addView(texts);

        if (r.action != null) {
            TextView btn = new TextView(this);
            btn.setText(r.action);
            btn.setTextColor(SysInfo.color(this, R.color.accent));
            btn.setTextSize(14f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(14), dp(8), dp(14), dp(8));
            btn.setBackgroundResource(R.drawable.bg_btn_tint_blue);
            btn.setClickable(true);
            btn.setOnClickListener(v -> r.run.run());
            line.addView(btn);
            r.btn = btn;
        }

        wrap.addView(line);
        parent.addView(wrap);
        r.root = wrap;
    }

    // ---------------------------------------------------------------- pemantauan

    /** Uji nyata: jalankan perintah "id" lewat Shizuku di thread terpisah. */
    private void runTestIfNeeded() {
        ticks++;
        if (!ShizukuHelper.granted()) {
            testOk = false;
            return;
        }
        // Jika sudah berhasil, cukup cek ulang tiap 5 detik; jika belum, coba tiap detik.
        if (testOk && ticks % 5 != 0) return;
        if (!testBusy.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    String out = ShizukuHelper.exec("id");
                    ok = out != null && out.contains("uid=");
                } catch (Throwable t) {
                    ok = false;
                } finally {
                    testBusy.set(false);
                }
                testOk = ok;
            }
        }).start();
    }

    private void refresh() {
        boolean first = true;
        Row firstBadRequired = null;
        for (Row r : rows) {
            boolean show = r.visible == null || r.visible.ok();
            r.root.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) continue;
            r.divider.setVisibility(first ? View.GONE : View.VISIBLE);
            first = false;

            r.ok = r.check.ok();
            r.dot.setBackgroundTintList(ColorStateList.valueOf(
                    SysInfo.color(this, r.ok ? R.color.ok : R.color.danger)));
            r.icon.setImageResource(r.ok ? R.drawable.ic_check : R.drawable.ic_xmark);
            r.hint.setText(r.ok ? r.hintGreen : r.hintRed);
            r.hint.setTextColor(SysInfo.color(this, r.ok ? R.color.ok : R.color.muted));
            if (r.btn != null) r.btn.setVisibility(r.ok ? View.GONE : View.VISIBLE);
            if (r.required && !r.ok && firstBadRequired == null) firstBadRequired = r;
        }

        if (firstBadRequired == null) {
            sumDot.setBackgroundTintList(ColorStateList.valueOf(SysInfo.color(this, R.color.ok)));
            sumIcon.setImageResource(R.drawable.ic_check);
            sumTitle.setText("Siap membekukan");
            sumSub.setText(ShizukuHelper.isRoot() ? "Shizuku aktif • mode root"
                    : "Shizuku aktif • tanpa root");
        } else {
            sumDot.setBackgroundTintList(ColorStateList.valueOf(SysInfo.color(this, R.color.danger)));
            sumIcon.setImageResource(R.drawable.ic_xmark);
            sumTitle.setText("Belum siap");
            sumSub.setText("Selanjutnya: " + firstBadRequired.title);
        }
    }

    // ---------------------------------------------------------------- aksi

    private void openPlayStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ShizukuHelper.SHIZUKU_PKG)));
        } catch (Exception e) {
            open(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + ShizukuHelper.SHIZUKU_PKG)));
        }
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PKG);
        if (i == null) {
            toast("Shizuku belum terpasang");
        } else {
            open(i);
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Tidak bisa membuka halaman ini");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
