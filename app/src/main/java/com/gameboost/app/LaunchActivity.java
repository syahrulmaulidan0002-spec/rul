package com.gameboost.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Animasi masuk game bergaya iOS: ikon dan cincin tipis di tengah, lalu langkah yang benar-benar
 * dijalankan muncul satu per satu dengan centang hijau. Setelah siap, layar memudar dan game terbuka.
 * Ketuk layar untuk mempercepat.
 */
public class LaunchActivity extends Activity {

    static final String EXTRA_PKG = "pkg";
    private static final long MIN_STEP_MS = 420;
    private static final long TIMEOUT_MS = 15000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, View[]> rows = new HashMap<>();   // key -> {indikator, detail}
    private volatile boolean fast = false;
    private boolean launched = false;
    private int planned = 0;
    private int doneCount = 0;
    private String pkg;

    private View launchRoot, launchIcon;
    private LaunchRing ring;
    private TextView title, sub;
    private LinearLayout steps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) {
            finish();
            return;
        }
        String missing = PermHelper.missing(this);
        if (missing != null) {
            Toast.makeText(this, "Atur dulu: " + missing, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        if (GameService.running) {                       // sudah aktif: langsung buka game saja
            GameLauncher.launch(this, pkg);
            finish();
            return;
        }

        setContentView(R.layout.activity_launch);
        launchRoot = findViewById(R.id.launchRoot);
        launchIcon = findViewById(R.id.launchIcon);
        ring = findViewById(R.id.launchRing);
        title = findViewById(R.id.launchTitle);
        sub = findViewById(R.id.launchSub);
        steps = findViewById(R.id.launchSteps);

        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            ((ImageView) launchIcon).setImageDrawable(d);
            title.setText(String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0))));
        } catch (Exception e) {
            title.setText(pkg);
        }

        planned = SessionRunner.planned(this);
        launchRoot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fast = true; }
        });

        // Masuk: ikon muncul membesar halus, teks menyusul.
        launchIcon.setScaleX(0.7f);
        launchIcon.setScaleY(0.7f);
        launchIcon.setAlpha(0f);
        launchIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
        title.setAlpha(0f);
        sub.setAlpha(0f);
        title.animate().alpha(1f).setStartDelay(150).setDuration(350).start();
        sub.animate().alpha(1f).setStartDelay(250).setDuration(350).start();

        startWork();
        ui.postDelayed(new Runnable() {
            @Override public void run() { launchGame(); }
        }, TIMEOUT_MS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Selama persiapan berjalan, tombol kembali diabaikan supaya proses tidak setengah jalan.
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- pekerjaan nyata

    private void startWork() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SessionRunner.run(app, pkg, new SessionRunner.Listener() {
                        private long beganAt = 0;

                        @Override public void begin(final String key) {
                            beganAt = SessionRunner.now();
                            ui.post(new Runnable() {
                                @Override public void run() { addRow(key); }
                            });
                        }

                        @Override public void done(final String key, final String detail) {
                            if (!fast) {
                                SessionRunner.sleepQuiet(MIN_STEP_MS - (SessionRunner.now() - beganAt));
                            }
                            ui.post(new Runnable() {
                                @Override public void run() { finishRow(key, detail); }
                            });
                        }
                    }, true);
                } catch (Throwable ignored) {
                    // Jika persiapan gagal, game tetap dibuka.
                }
                ui.post(new Runnable() {
                    @Override public void run() { finishUp(); }
                });
            }
        }).start();
    }

    private String labelFor(String key) {
        switch (key) {
            case "kill":
                return Prefs.flag(this, "freeze") && ShizukuHelper.ready()
                        ? "Membekukan aplikasi latar" : "Menutup aplikasi latar";
            case "perf": return "Mode performa";
            case "net": return "Memfokuskan internet";
            default: return "Membisukan notifikasi";
        }
    }

    private void addRow(String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        FrameLayout slot = new FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(0xFF8E8E93));
        slot.addView(pb, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(slot);

        TextView label = new TextView(this);
        label.setText(labelFor(key));
        label.setTextColor(SysInfo.color(this, R.color.text));
        label.setTextSize(15f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.setMarginStart(dp(12));
        row.addView(label, llp);

        TextView detail = new TextView(this);
        detail.setTextColor(SysInfo.color(this, R.color.muted));
        detail.setTextSize(13f);
        detail.setSingleLine(true);
        row.addView(detail);

        row.setAlpha(0f);
        row.setTranslationY(dp(12));
        steps.addView(row);
        row.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();

        rows.put(key, new View[]{slot, detail});
        if (planned > 0) ring.setProgress((doneCount + 0.45f) / planned);
    }

    private void finishRow(String key, String detail) {
        View[] r = rows.get(key);
        if (r == null) return;
        FrameLayout slot = (FrameLayout) r[0];
        slot.removeAllViews();
        ImageView ck = new ImageView(this);
        ck.setImageResource(R.drawable.ic_check);
        ck.setColorFilter(SysInfo.color(this, R.color.ok));
        slot.addView(ck, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        ck.setScaleX(0.3f);
        ck.setScaleY(0.3f);
        ck.animate().scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new OvershootInterpolator(2f)).start();
        ((TextView) r[1]).setText(detail);
        doneCount++;
        if (planned > 0) ring.setProgress(Math.min(1f, doneCount / (float) planned));
    }

    private void finishUp() {
        if (launched) return;
        ring.setDone();
        sub.setText("Siap");
        sub.setTextColor(SysInfo.color(this, R.color.ok));
        launchRoot.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
        ui.postDelayed(new Runnable() {
            @Override public void run() { launchGame(); }
        }, fast ? 80 : 520);
    }

    /** Layar memudar sambil sedikit membesar, lalu game terbuka. */
    private void launchGame() {
        if (launched) return;
        launched = true;
        if (launchRoot != null) {
            launchRoot.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(260).start();
        }
        if (!GameLauncher.launch(this, pkg)) {
            Toast.makeText(this, "Game tidak bisa dibuka", Toast.LENGTH_LONG).show();
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }, 320);
    }
}
