package com.gameboost.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Animasi masuk game bergaya iOS: ikon dan cincin tipis di tengah, lalu langkah yang benar-benar
 * dijalankan muncul satu per satu dengan centang hijau. Setelah siap, layar memudar dan game terbuka.
 * Ketuk layar untuk mempercepat.
 */
public class LaunchActivity extends Activity {

    static final String EXTRA_PKG = "pkg";
    private static final long MIN_STEP_MS = 160;
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
    private FrameLayout stage;
    private View ramBox;
    private TextView ramNum, ramDelta;
    private final List<View> orbit = new ArrayList<>();
    private final List<float[]> orbitDir = new ArrayList<>();
    private long ramShown = 0;
    private boolean flewAway = false;

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
        stage = findViewById(R.id.launchStage);
        ramBox = findViewById(R.id.launchRamBox);
        ramNum = findViewById(R.id.launchRam);
        ramDelta = findViewById(R.id.launchRamDelta);
        ramShown = GameBooster.availMbNow(this);
        ramNum.setText(fmtRam(ramShown));
        ramBox.animate().alpha(1f).setStartDelay(350).setDuration(400).start();

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
        if (Prefs.flag(this, "kill")) prepareOrbit();
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
                                @Override public void run() {
                                    finishRow(key, detail);
                                    if ("kill".equals(key)) onKillDone();
                                }
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
        launchRoot.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (planned > 0) ring.setProgress(Math.min(1f, doneCount / (float) planned));
    }

    private void finishUp() {
        if (launched) return;
        ring.setDone();
        ring.animate().scaleX(1.07f).scaleY(1.07f).setDuration(160).withEndAction(new Runnable() {
            @Override public void run() {
                if (ring != null) ring.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(2f)).start();
            }
        }).start();
        flyAway();
        sub.setText("Siap");
        sub.setTextColor(SysInfo.color(this, R.color.ok));
        launchRoot.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
        ui.postDelayed(new Runnable() {
            @Override public void run() { launchGame(); }
        }, fast ? 60 : 240);
    }

    /** Ikon game melebar memenuhi layar seperti membuka aplikasi di iOS, lalu game terbuka. */
    private void launchGame() {
        if (launched) return;
        launched = true;
        if (launchRoot != null) {
            float screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            float k = screen / dp(96) * 1.25f;
            View[] others = {title, sub, steps, ramBox, ring, stage};
            for (View v : others) v.animate().alpha(0f).setStartDelay(0).setDuration(170).start();
            launchIcon.animate().scaleX(k).scaleY(k).alpha(0f).setStartDelay(0).setDuration(430)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!GameLauncher.launch(LaunchActivity.this, pkg)) {
                    Toast.makeText(LaunchActivity.this, "Game tidak bisa dibuka", Toast.LENGTH_LONG).show();
                }
            }
        }, 150);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }, 450);
    }

    // ---------------------------------------------------------------- orbit ikon aplikasi

    private static String fmtRam(long mb) {
        return mb >= 1000 ? String.format(Locale.US, "%.2f GB", mb / 1024f) : mb + " MB";
    }

    /** Ambil ikon aplikasi yang akan dihentikan, lalu tampilkan mengorbit di sekitar ikon game. */
    private void prepareOrbit() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Drawable> icons = new ArrayList<>();
                try {
                    PackageManager pm = app.getPackageManager();
                    Set<String> targets = GameBooster.targets(app, pkg);
                    for (String p : targets) {
                        if (icons.size() >= 10) break;
                        if (pm.getLaunchIntentForPackage(p) == null) continue;   // hanya yang punya ikon
                        try {
                            icons.add(pm.getApplicationIcon(p));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                ui.post(new Runnable() {
                    @Override public void run() { showOrbit(icons); }
                });
            }
        }).start();
    }

    private void showOrbit(final List<Drawable> icons) {
        if (launched || flewAway || icons.isEmpty() || stage == null) return;
        stage.post(new Runnable() {
            @Override public void run() {
                if (launched || flewAway) return;
                int[] a = new int[2], b = new int[2];
                ring.getLocationInWindow(a);
                stage.getLocationInWindow(b);
                float cx = a[0] - b[0] + ring.getWidth() / 2f;
                float cy = a[1] - b[1] + ring.getHeight() / 2f;
                int n = icons.size();
                int sz = dp(34);
                float rad = dp(104);
                for (int i = 0; i < n; i++) {
                    double ang = Math.toRadians(-90 + i * 360.0 / n);
                    float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                    ImageView v = new ImageView(LaunchActivity.this);
                    v.setImageDrawable(icons.get(i));
                    v.setBackgroundResource(R.drawable.bg_app_icon);
                    v.setClipToOutline(true);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sz, sz);
                    lp.leftMargin = Math.round(cx + dx * rad - sz / 2f);
                    lp.topMargin = Math.round(cy + dy * rad - sz / 2f);
                    v.setAlpha(0f);
                    v.setScaleX(0.3f);
                    v.setScaleY(0.3f);
                    stage.addView(v, lp);
                    orbit.add(v);
                    orbitDir.add(new float[]{dx, dy});
                    v.animate().alpha(0.95f).scaleX(1f).scaleY(1f).setStartDelay(i * 55L).setDuration(320)
                            .setInterpolator(new OvershootInterpolator(1.4f)).start();
                }
            }
        });
    }

    /** Aplikasi latar selesai ditutup: ikonnya mengecil dan terbang keluar satu per satu. */
    private void flyAway() {
        if (flewAway) return;
        flewAway = true;
        for (int i = 0; i < orbit.size(); i++) {
            final View v = orbit.get(i);
            float[] d = orbitDir.get(i);
            v.animate().translationX(d[0] * dp(120)).translationY(d[1] * dp(120))
                    .scaleX(0.15f).scaleY(0.15f).alpha(0f)
                    .setStartDelay(i * 45L).setDuration(460)
                    .setInterpolator(new AccelerateInterpolator(1.3f)).start();
        }
    }

    /** Pembersihan selesai: ikon terbang keluar dan angka RAM bebas naik. */
    private void onKillDone() {
        flyAway();
        final long from = ramShown;
        long to = GameBooster.lastAfterMb;
        if (to <= from) return;
        ramShown = to;
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(800);
        va.setStartDelay(250);
        va.setInterpolator(new DecelerateInterpolator(1.5f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an) {
                ramNum.setText(fmtRam(Math.round((Float) an.getAnimatedValue())));
            }
        });
        va.start();
        ramDelta.setText("+" + (to - from) + " MB");
        Icons.start(ramDelta, R.drawable.ic_arrow_up, 11, SysInfo.color(this, R.color.ok));
        ramDelta.animate().alpha(1f).setStartDelay(450).setDuration(350).start();
    }
}
