package com.gameboost.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Panduan langkah demi langkah: menyalakan Shizuku (tanpa root atau dengan root) lalu cara
 * memakai GameBoost. Setiap langkah dicek otomatis dan pindah sendiri saat sudah beres.
 */
public class GuideActivity extends Activity {

    private interface Check { boolean ok(); }

    private static final class Step {
        final String title, body, action;
        final int icon, tint;
        final Runnable run;
        final Check check;
        final boolean choose;

        Step(String title, String body, int icon, int tint, String action, Runnable run,
             Check check, boolean choose) {
            this.title = title;
            this.body = body;
            this.icon = icon;
            this.tint = tint;
            this.action = action;
            this.run = run;
            this.check = check;
            this.choose = choose;
        }
    }

    private static final int REQ_SHIZUKU = 100;

    private final List<Step> steps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int index = 0;
    private boolean startedOk = false;
    private boolean advancing = false;

    private TextView gStep, gTitle, gBody, gStatus, gBack;
    private ImageView gIcon;
    private View gTile, gChoices;
    private Button gAction, gNext;
    private LinearLayout gDots;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            checkCurrent();
            handler.postDelayed(this, 800);
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
                v.setPadding(i.left + dp(24), i.top + dp(16), i.right + dp(24), i.bottom + dp(16));
                return WindowInsets.CONSUMED;
            });
        }

        gStep = findViewById(R.id.gStep);
        gTitle = findViewById(R.id.gTitle);
        gBody = findViewById(R.id.gBody);
        gStatus = findViewById(R.id.gStatus);
        gBack = findViewById(R.id.gBack);
        gIcon = findViewById(R.id.gIcon);
        gTile = findViewById(R.id.gTile);
        gChoices = findViewById(R.id.gChoices);
        gAction = findViewById(R.id.gAction);
        gNext = findViewById(R.id.gNext);
        gDots = findViewById(R.id.gDots);

        steps.add(new Step("Mau pakai cara apa?",
                "Dengan Shizuku, GameBoost bisa membekukan semua aplikasi lain selama kamu main. "
                        + "Pilih sesuai HP kamu.",
                R.drawable.ic_gamepad, 0xFF0A84FF, null, null, null, true));

        findViewById(R.id.gPathNoRoot).setOnClickListener(v -> startPath(false));
        findViewById(R.id.gPathRoot).setOnClickListener(v -> startPath(true));
        findViewById(R.id.gPathSkip).setOnClickListener(v -> finish());

        gBack.setOnClickListener(v -> {
            if (index > 0) {
                index--;
                show();
            } else {
                finish();
            }
        });
        gNext.setOnClickListener(v -> {
            if (index >= steps.size() - 1) {
                finish();
            } else {
                index++;
                show();
            }
        });
        gAction.setOnClickListener(v -> {
            Step s = steps.get(index);
            if (s.run != null) s.run.run();
        });

        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- isi panduan

    private void startPath(boolean root) {
        while (steps.size() > 1) steps.remove(steps.size() - 1);

        steps.add(new Step("Pasang Shizuku",
                "Shizuku adalah aplikasi kecil yang memberi GameBoost izin untuk membekukan aplikasi lain. "
                        + "Pasang dulu dari Play Store.",
                R.drawable.ic_check, 0xFF30D158, "Buka Play Store", this::openPlayStore,
                () -> ShizukuHelper.installed(this), false));

        if (root) {
            steps.add(new Step("Nyalakan Shizuku",
                    "Buka Shizuku, lalu ketuk \"Start (root)\". Pilih Izinkan di jendela Magisk atau KernelSU. "
                            + "Pilih \"Selalu\" supaya nyala sendiri tiap HP restart.",
                    R.drawable.ic_bolt, 0xFF64D2FF, "Buka Shizuku", this::openShizuku,
                    ShizukuHelper::running, false));
        } else {
            steps.add(new Step("Aktifkan Opsi pengembang",
                    "Buka Pengaturan → Tentang ponsel, lalu ketuk \"Nomor build\" 7 kali.\n"
                            + "Di HyperOS: ketuk \"Versi OS\" 7 kali.",
                    R.drawable.ic_tune, 0xFFAF52DE, "Buka Tentang ponsel",
                    () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
                    () -> Settings.Global.getInt(getContentResolver(),
                            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1, false));

            steps.add(new Step("Nyalakan Debugging nirkabel",
                    "Di Opsi pengembang, nyalakan \"Debugging nirkabel\". HP harus tersambung ke Wi-Fi.",
                    R.drawable.ic_window, 0xFF0A84FF, "Buka Opsi pengembang",
                    () -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
                    () -> Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1,
                    false));

            steps.add(new Step("Nyalakan Shizuku",
                    "Buka Shizuku, ketuk \"Pairing\", masukkan kodenya, lalu ketuk \"Start\".\n"
                            + "Di HyperOS, nyalakan juga \"Debugging USB (pengaturan keamanan)\" di Opsi pengembang.",
                    R.drawable.ic_bolt, 0xFF64D2FF, "Buka Shizuku", this::openShizuku,
                    ShizukuHelper::running, false));
        }

        steps.add(new Step("Izinkan GameBoost",
                "Ketuk tombol di bawah, lalu pilih \"Izinkan selalu\".",
                R.drawable.ic_check, 0xFF30D158, "Beri izin", () -> {
                    if (!ShizukuHelper.running()) {
                        Toast.makeText(this, "Shizuku belum menyala. Ulangi langkah sebelumnya.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        ShizukuHelper.requestPermission(REQ_SHIZUKU);
                    }
                }, ShizukuHelper::granted, false));

        steps.add(new Step("1. Pilih game",
                "Di layar utama GameBoost, ketuk game yang mau dimainkan sampai ada centang biru.",
                R.drawable.ic_gamepad, 0xFF32ADE6, null, null, null, false));

        steps.add(new Step("2. Mulai Mode Game",
                "Ketuk \"Mulai Mode Game\". Aplikasi lain dibekukan, notifikasi dibisukan, "
                        + "dan game terbuka sendiri.",
                R.drawable.ic_bolt, 0xFFFF9F0A, null, null, null, false));

        steps.add(new Step("3. Selesai main",
                "Ketuk \"Selesai\" di jendela mengambang. Semua aplikasi kembali normal. "
                        + "Kalau ada ikon yang masih abu-abu, ketuk \"Pulihkan semua\" di layar utama.",
                R.drawable.ic_check, 0xFF30D158, null, null, null, false));

        index = 1;
        show();
    }

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
            Toast.makeText(this, "Shizuku belum terpasang", Toast.LENGTH_LONG).show();
        } else {
            open(i);
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka halaman ini", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- tampilan

    private void show() {
        advancing = false;
        Step s = steps.get(index);

        gStep.setText(index == 0 ? "MULAI" : "LANGKAH " + index + " DARI " + (steps.size() - 1));
        gTitle.setText(s.title);
        gBody.setText(s.body);
        gIcon.setImageResource(s.icon);
        gTile.setBackgroundTintList(ColorStateList.valueOf(s.tint));

        gChoices.setVisibility(s.choose ? View.VISIBLE : View.GONE);
        gAction.setVisibility(!s.choose && s.action != null ? View.VISIBLE : View.GONE);
        if (s.action != null) gAction.setText(s.action);

        gBack.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        gNext.setVisibility(s.choose ? View.GONE : View.VISIBLE);
        gNext.setText(index == steps.size() - 1 ? "Selesai" : "Lanjut");

        startedOk = s.check != null && s.check.ok();
        updateStatus(s);
        buildDots();
    }

    private void updateStatus(Step s) {
        if (s.check == null) {
            gStatus.setText("");
            return;
        }
        if (s.check.ok()) {
            gStatus.setText("✓ Sudah beres");
            gStatus.setTextColor(SysInfo.color(this, R.color.ok));
        } else {
            gStatus.setText("Belum beres");
            gStatus.setTextColor(SysInfo.color(this, R.color.warn));
        }
    }

    /** Dipanggil berkala: perbarui status, dan pindah otomatis bila langkah baru saja beres. */
    private void checkCurrent() {
        Step s = steps.get(index);
        if (s.check == null) return;
        updateStatus(s);
        if (!startedOk && !advancing && s.check.ok() && index < steps.size() - 1) {
            advancing = true;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (advancing && index < steps.size() - 1) {
                        index++;
                        show();
                    }
                }
            }, 700);
        }
    }

    private void buildDots() {
        gDots.removeAllViews();
        for (int i = 0; i < steps.size(); i++) {
            View d = new View(this);
            int size = dp(i == index ? 8 : 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(4), 0, dp(4), 0);
            d.setLayoutParams(lp);
            d.setBackgroundResource(R.drawable.dot_step);
            d.setBackgroundTintList(ColorStateList.valueOf(
                    i == index ? SysInfo.color(this, R.color.accent) : 0xFF48484A));
            gDots.addView(d);
        }
    }
}
