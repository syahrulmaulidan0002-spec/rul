package com.gameboost.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Halaman Pengaturan: izin yang dibutuhkan dan saklar mode game. */
public class SettingsActivity extends Activity {

    private static final int REQ_NOTIF = 21;

    private interface Check { boolean ok(); }

    private static final class Perm {
        final Check check;          // null = tidak bisa dicek (hanya tombol "Buka")
        final Runnable action;
        TextView status;

        Perm(Check check, Runnable action) {
            this.check = check;
            this.action = action;
        }
    }

    private final List<Perm> perms = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = Prefs.get(this);

        if (Build.VERSION.SDK_INT >= 35) {
            findViewById(R.id.rootSettings).setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvVendorHint)).setText(
                VendorHelper.hint() + "\n\nAndroid 13+: jika \"Akses notifikasi\" abu-abu, buka Info Aplikasi "
                        + "GameBoost, ketuk titik tiga di kanan atas, lalu pilih \"Izinkan setelan terbatas\".");

        buildPermRows((LinearLayout) findViewById(R.id.permList));
        buildSwitchRows((LinearLayout) findViewById(R.id.switchList));
        LinearLayout rec = findViewById(R.id.recSwitchList);
        addSwitch(rec, "Rekam suara game", R.drawable.ic_sparkle, 0xFF0A84FF, RecordService.PREF_GAME);
        addSwitch(rec, "Rekam suara mikrofon (untuk konten)", R.drawable.ic_mic, 0xFFFF453A, RecordService.PREF_MIC);
        buildPreview();
        buildSaturation();
        buildRecordQuality();
        buildRecordPreset();
        android.view.ViewGroup content = findViewById(R.id.settingsContent);
        Motion.pressAll(content);
        Motion.pressAll(findViewById(R.id.btnBack));
        Motion.stagger(content, 60);
    }

    // ---------------------------------------------------------------- simulasi panel

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View previewRoot;
    private SparkView[] previewSparks;
    private TextView[] previewVals;
    private int previewTick = 0;
    private final Runnable previewStep = new Runnable() {
        @Override public void run() {
            previewTick++;
            double t = previewTick * 0.5;
            float[] v = {
                    (float) (45 + 30 * Math.sin(t) + 8 * Math.sin(t * 2.7)),
                    (float) (55 + 25 * Math.sin(t * 0.8 + 1) + 6 * Math.sin(t * 3.1)),
                    (float) (62 + 8 * Math.sin(t * 0.4) + 3 * Math.sin(t * 2)),
                    (float) (180 + 140 * Math.sin(t * 0.9 + 2) + 40 * Math.sin(t * 3))};
            String[] txt = {Math.round(v[0]) + "%", Math.round(v[1]) + "%", Math.round(v[2]) + "%",
                    Math.max(1, Math.round(v[3])) + " KB/s"};
            for (int i = 0; i < 4; i++) {
                previewSparks[i].push(Math.max(0f, v[i]));
                previewVals[i].setText(txt[i]);
            }
            ui.postDelayed(this, 1300);
        }
    };

    private void buildRecordPreset() {
        final TextView val = findViewById(R.id.tvRecPreset);
        SeekBar sb = findViewById(R.id.seekPreset);
        int cur = Math.max(0, Math.min(2, prefs.getInt(RecordService.PREF_PRESET, 0)));
        sb.setProgress(cur);
        val.setText(RecordService.PRESET_LABEL[cur]);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(RecordService.PRESET_LABEL[p]);
                if (!user) return;
                // Preset mengatur resolusi rekam, resolusi game, dan kunci 60 Hz sekaligus.
                int res = p == 2 ? 1080 : 720;
                prefs.edit().putInt(RecordService.PREF_PRESET, p).putInt(RecordService.PREF_RES, res)
                        .putBoolean(RecordService.PREF_FULLSCALE, p >= 1)
                        .putBoolean(RecordService.PREF_LOWHZ, p <= 1).apply();
                SeekBar rs = findViewById(R.id.seekRec);
                for (int i = 0; i < RecordService.RES.length; i++) if (RecordService.RES[i] == res) rs.setProgress(i);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void buildRecordQuality() {
        final TextView val = findViewById(R.id.tvRecVal);
        SeekBar sb = findViewById(R.id.seekRec);
        int cur = prefs.getInt(RecordService.PREF_RES, RecordService.DEFAULT_RES);
        int idx = 2;
        for (int i = 0; i < RecordService.RES.length; i++) if (RecordService.RES[i] == cur) idx = i;
        sb.setProgress(idx);
        val.setText(RecordService.RES_LABEL[idx]);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(RecordService.RES_LABEL[p]);
                prefs.edit().putInt(RecordService.PREF_RES, RecordService.RES[p]).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void buildSaturation() {
        final SatPreviewView pv = findViewById(R.id.satPreview);
        final TextView val = findViewById(R.id.tvSatVal);
        SeekBar sb = findViewById(R.id.seekSat);
        int cur = Math.max(100, Math.min(140, prefs.getInt(GameBooster.PREF_SAT, 100)));
        sb.setProgress(cur - 100);
        pv.setSaturation(cur);
        val.setText(cur <= 100 ? "Mati" : "+" + (cur - 100) + "%");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                pv.setSaturation(100 + p);
                val.setText(p <= 0 ? "Mati" : "+" + p + "%");
                prefs.edit().putInt(GameBooster.PREF_SAT, 100 + p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        findViewById(R.id.btnResetSat).setOnClickListener(v -> new Thread(() -> {
            final boolean ok = GameBooster.resetSaturation(this);
            ui.post(() -> toast(ok ? "Warna layar dikembalikan" : "Shizuku belum siap"));
        }).start());
    }

    private void buildPreview() {
        FrameLayout box = findViewById(R.id.previewBox);
        View pr = LayoutInflater.from(this).inflate(R.layout.overlay_panel, box, false);
        previewRoot = pr;
        pr.findViewById(R.id.handle).setVisibility(View.GONE);
        pr.findViewById(R.id.card).setVisibility(View.VISIBLE);
        pr.findViewById(R.id.btnCollapse).setVisibility(View.INVISIBLE);
        ((BatteryView) pr.findViewById(R.id.battery)).setLevel(78, false);
        ((TextView) pr.findViewById(R.id.tvBatt)).setText("78%");
        ((TextView) pr.findViewById(R.id.tvTemp)).setText("36.2°C");
        TextView pf = pr.findViewById(R.id.tvFps);
        pf.setText("60 FPS");
        pf.setTextColor(0xFF30D158);
        ((TextView) pr.findViewById(R.id.tvNetFocus)).setText("Fokus ke game");
        ((TextView) pr.findViewById(R.id.tvHint)).setText("Performa stabil");
        ((ImageView) pr.findViewById(R.id.icNetFocus)).setImageTintList(ColorStateList.valueOf(0xFF30D158));

        LinearLayout r1 = pr.findViewById(R.id.gridRow1);
        LinearLayout r2 = pr.findViewById(R.id.gridRow2);
        View[] cells = {
                PanelStyle.addCell(r1, "CPU", 0xFF0A84FF, 100f, 1f),
                PanelStyle.addCell(r1, "GPU", 0xFFBF5AF2, 100f, 1f),
                PanelStyle.addCell(r2, "RAM", 0xFF30D158, 100f, 1f),
                PanelStyle.addCell(r2, "INTERNET", 0xFFFF9F0A, 0f, 64f)};
        previewSparks = new SparkView[4];
        previewVals = new TextView[4];
        for (int i = 0; i < 4; i++) {
            previewSparks[i] = cells[i].findViewById(R.id.cellSpark);
            previewVals[i] = cells[i].findViewById(R.id.cellValue);
            ((TextView) cells[i].findViewById(R.id.cellSub)).setText(i == 3 ? "unduh" : "stabil");
        }
        for (int i = 0; i < 12; i++) previewStep.run();   // isi awal supaya grafik langsung terlihat
        ui.removeCallbacks(previewStep);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        box.addView(pr, lp);

        int cur = PanelStyle.opacity(this);
        PanelStyle.apply(pr, cur);
        final TextView val = findViewById(R.id.tvGlassVal);
        val.setText(cur + "%");
        SeekBar sb = findViewById(R.id.seekGlass);
        sb.setProgress(cur);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(p + "%");
                PanelStyle.apply(previewRoot, p);
                prefs.edit().putInt(PanelStyle.KEY, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }


    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.pop_in, R.anim.pop_out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(previewStep);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermUi();
        GameService.syncWatch(this);
        ui.removeCallbacks(previewStep);
        ui.postDelayed(previewStep, 1300);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] results) {
        super.onRequestPermissionsResult(code, p, results);
        updatePermUi();
    }

    // ---------------------------------------------------------------- baris izin

    private void buildPermRows(LinearLayout parent) {
        addPerm(parent, "Jendela mengambang", R.drawable.ic_window, 0xFF0A84FF,
                () -> PermHelper.canOverlay(this), () -> PermHelper.openOverlaySettings(this));

        addPerm(parent, "Akses Jangan Ganggu", R.drawable.ic_moon, 0xFF5E5CE6,
                () -> GameBooster.hasDndAccess(this), () -> {
                    if (Build.VERSION.SDK_INT < 23) {
                        toast("Jangan Ganggu otomatis tidak didukung di Android ini");
                    } else {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    }
                });

        addPerm(parent, "Akses notifikasi (hapus otomatis)", R.drawable.ic_bell, 0xFFFF453A,
                () -> GameNotifService.isEnabled(this), () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Exception e) {
                        toast("Buka Pengaturan > Akses notifikasi secara manual");
                    }
                });

        addPerm(parent, "Akses penggunaan (aktif otomatis)", R.drawable.ic_sparkle, 0xFFBF5AF2,
                () -> PermHelper.hasUsageAccess(this), () -> PermHelper.openUsageAccess(this));

        addPerm(parent, "Notifikasi mode game", R.drawable.ic_bolt, 0xFF30D158,
                () -> PermHelper.hasNotifPerm(this), () -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                });

        addPerm(parent, "Tanpa batasan baterai", R.drawable.ic_battery, 0xFF34C759,
                () -> PermHelper.ignoresBatteryOpt(this), () -> PermHelper.requestIgnoreBattery(this));

        addPerm(parent, "Akses Shizuku (bekukan & performa)", R.drawable.ic_bolt, 0xFF64D2FF,
                ShizukuHelper::granted, this::openGuide);

        addPerm(parent, "Panduan Shizuku & root", R.drawable.ic_tune, 0xFFAF52DE,
                null, this::openGuide);

        addPerm(parent, VendorHelper.title(), R.drawable.ic_tune, 0xFFFF9F0A,
                null, () -> VendorHelper.open(this));
    }

    private void addPerm(LinearLayout parent, String title, int icon, int tint, Check check, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.row_perm, parent, false);
        row.findViewById(R.id.permDivider).setVisibility(parent.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.permTile).setBackgroundTintList(ColorStateList.valueOf(tint));
        ((ImageView) row.findViewById(R.id.permIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.permTitle)).setText(title);

        final Perm p = new Perm(check, action);
        p.status = row.findViewById(R.id.permStatus);
        row.findViewById(R.id.permRow).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Baris yang sudah aktif tidak perlu dibuka lagi.
                if (p.check == null || !p.check.ok()) p.action.run();
            }
        });
        perms.add(p);
        parent.addView(row);
    }

    private void updatePermUi() {
        for (Perm p : perms) {
            if (p.check == null) {
                p.status.setText("Buka");
                p.status.setTextColor(SysInfo.color(this, R.color.accent));
            } else if (p.check.ok()) {
                p.status.setText("Aktif");
                p.status.setTextColor(SysInfo.color(this, R.color.ok));
            } else {
                p.status.setText("Atur");
                p.status.setTextColor(SysInfo.color(this, R.color.accent));
            }
        }
    }

    // ---------------------------------------------------------------- baris saklar

    private void buildSwitchRows(LinearLayout parent) {
        addSwitch(parent, "Aktif otomatis saat game dibuka", R.drawable.ic_bolt, 0xFFBF5AF2, "auto");
        addSwitch(parent, "Animasi masuk game", R.drawable.ic_sparkle, 0xFF0A84FF, "anim");
        addSwitch(parent, "Tutup semua aplikasi latar belakang", R.drawable.ic_broom, 0xFFFF9F0A, "kill");
        addSwitch(parent, "Hapus semua notifikasi yang masuk", R.drawable.ic_bell, 0xFFFF453A, "notif");
        addSwitch(parent, "Hening total (Jangan Ganggu)", R.drawable.ic_moon, 0xFF5E5CE6, "dnd");
        addSwitch(parent, "Jendela mengambang saat bermain", R.drawable.ic_window, 0xFF0A84FF, "overlay");
        addSwitch(parent, "Panel mengambang di sisi kiri", R.drawable.ic_window, 0xFF5E5CE6, "panel_left");
        addSwitch(parent, "Bekukan, bukan sekadar tutup (Shizuku)", R.drawable.ic_sparkle, 0xFF64D2FF, "freeze");
        addSwitch(parent, "Bekukan sampai ke akar (termasuk tanpa ikon)", R.drawable.ic_sparkle, 0xFF64D2FF, "deep");
        addSwitch(parent, "Turunkan resolusi game 20% (FPS naik, Android 13+)", R.drawable.ic_bolt, 0xFFFF9F0A, "downscale");
        addSwitch(parent, "Putus internet latar belakang (Shizuku)", R.drawable.ic_wifi, 0xFF30D158, "netfocus");
        addSwitch(parent, "Mode performa: refresh rate maks (Shizuku)", R.drawable.ic_bolt, 0xFF30D158, "perf");
        addSwitch(parent, "Buka game di jendela kecil", R.drawable.ic_gamepad, 0xFF32ADE6, "freeform");
        addSwitch(parent, "Pengingat istirahat", R.drawable.ic_moon, 0xFF5E5CE6, "breakremind");
        addIntervalRow(parent);
    }

    private void addSwitch(LinearLayout parent, String title, int icon, int tint, final String key) {
        View row = getLayoutInflater().inflate(R.layout.row_switch, parent, false);
        row.findViewById(R.id.swDivider).setVisibility(parent.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.swTile).setBackgroundTintList(ColorStateList.valueOf(tint));
        ((ImageView) row.findViewById(R.id.swIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.swTitle)).setText(title);

        final Switch sw = row.findViewById(R.id.swSwitch);
        sw.setChecked(Prefs.flag(this, key));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
                if ("auto".equals(key)) GameService.syncWatch(SettingsActivity.this);
            }
        });
        row.findViewById(R.id.swRow).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row);
    }

    private static final int[] BREAK_STEPS = {30, 45, 60, 90, 120};

    /** Baris "Ingatkan setiap": ketuk untuk berganti 30 / 45 / 60 / 90 / 120 menit. */
    private void addIntervalRow(LinearLayout parent) {
        View row = getLayoutInflater().inflate(R.layout.row_perm, parent, false);
        row.findViewById(R.id.permDivider).setVisibility(View.VISIBLE);
        row.findViewById(R.id.permTile).setBackgroundTintList(ColorStateList.valueOf(0xFF5E5CE6));
        ((ImageView) row.findViewById(R.id.permIcon)).setImageResource(R.drawable.ic_tune);
        ((TextView) row.findViewById(R.id.permTitle)).setText("Ingatkan setiap");
        final TextView status = row.findViewById(R.id.permStatus);
        status.setTextColor(SysInfo.color(this, R.color.accent));
        status.setText(prefs.getInt("break_min", 60) + " menit");
        row.findViewById(R.id.permRow).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = prefs.getInt("break_min", 60);
                int next = BREAK_STEPS[0];
                for (int i = 0; i < BREAK_STEPS.length; i++) {
                    if (BREAK_STEPS[i] == cur) {
                        next = BREAK_STEPS[(i + 1) % BREAK_STEPS.length];
                        break;
                    }
                }
                prefs.edit().putInt("break_min", next).apply();
                status.setText(next + " menit");
            }
        });
        parent.addView(row);
    }

    private void openGuide() {
        startActivity(new Intent(this, GuideActivity.class));
        overridePendingTransition(R.anim.push_in, R.anim.push_back);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
