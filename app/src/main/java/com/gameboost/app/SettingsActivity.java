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
import android.view.View;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermUi();
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
        addSwitch(parent, "Tutup semua aplikasi latar belakang", R.drawable.ic_sparkle, 0xFFFF9F0A, "kill");
        addSwitch(parent, "Hapus semua notifikasi yang masuk", R.drawable.ic_bell, 0xFFFF453A, "notif");
        addSwitch(parent, "Hening total (Jangan Ganggu)", R.drawable.ic_moon, 0xFF5E5CE6, "dnd");
        addSwitch(parent, "Jendela mengambang saat bermain", R.drawable.ic_window, 0xFF0A84FF, "overlay");
        addSwitch(parent, "Panel mengambang di sisi kiri", R.drawable.ic_window, 0xFF5E5CE6, "panel_left");
        addSwitch(parent, "Bekukan, bukan sekadar tutup (Shizuku)", R.drawable.ic_sparkle, 0xFF64D2FF, "freeze");
        addSwitch(parent, "Mode performa: refresh rate maks (Shizuku)", R.drawable.ic_bolt, 0xFF30D158, "perf");
        addSwitch(parent, "Buka game di jendela kecil", R.drawable.ic_gamepad, 0xFF32ADE6, "freeform");
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
            }
        });
        row.findViewById(R.id.swRow).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row);
    }

    private void openGuide() {
        startActivity(new Intent(this, GuideActivity.class));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
