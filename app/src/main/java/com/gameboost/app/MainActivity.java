package com.gameboost.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String EXTRA_GAME = "game";
    static final String EXTRA_OVERLAY = "overlay";
    static final String EXTRA_FREED = "freed";
    static final String EXTRA_REQUESTED = "requested";
    static final String EXTRA_KILL = "kill";
    static final String EXTRA_NOTIF = "notif";
    static final String EXTRA_FREEZE = "freeze";

    private static final int REQ_NOTIF = 21;

    private static final String[] GAME_HINTS = {
            "legends", "freefire", "pubg", "codm", "callofduty", "genshin", "supercell",
            "roblox", "minecraft", "garena", "tencent", "mobilelegends", "honkai", "efootball"
    };

    /** Satu baris izin pada bagian IZIN. */
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

    private SharedPreferences prefs;
    private final List<GameItem> items = new ArrayList<>();
    private final List<Perm> perms = new ArrayList<>();
    private GameAdapter adapter;
    private String selectedPkg;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListView list;
    private RingView ringRam;
    private TextView tvRamDetail, tvTemp, tvBatt, tvGraphics, tvCount, tvSelected;
    private Switch swKill, swNotif, swDnd, swOverlay, swFreeform, swFreeze, swPerf;
    private View cardRestore;
    private TextView tvRestore;
    private Button btnMain;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateStatus();
            updateBottom();
            updateRestore();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("gameboost", MODE_PRIVATE);
        selectedPkg = prefs.getString("game_pkg", null);

        // Android 15 menggambar aplikasi sampai ke bawah status bar: beri ruang untuk bar sistem.
        if (Build.VERSION.SDK_INT >= 35) {
            findViewById(R.id.rootMain).setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        list = findViewById(R.id.list);
        btnMain = findViewById(R.id.btnMain);
        tvSelected = findViewById(R.id.tvSelected);

        View header = getLayoutInflater().inflate(R.layout.header_main, list, false);
        list.addHeaderView(header, null, false);

        ringRam = header.findViewById(R.id.ringRam);
        tvRamDetail = header.findViewById(R.id.tvRamDetail);
        tvTemp = header.findViewById(R.id.tvTemp);
        tvBatt = header.findViewById(R.id.tvBatt);
        tvGraphics = header.findViewById(R.id.tvGraphics);
        tvCount = header.findViewById(R.id.tvCount);
        tvGraphics.setText(SysInfo.advice(this));
        setupCreator(header);
        cardRestore = header.findViewById(R.id.cardRestore);
        tvRestore = header.findViewById(R.id.tvRestore);
        header.findViewById(R.id.rowRestore).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreAll(); }
        });
        ((TextView) header.findViewById(R.id.tvVendorHint)).setText(
                VendorHelper.hint() + "\n\nAndroid 13+: jika \"Akses notifikasi\" abu-abu, buka Info Aplikasi "
                        + "GameBoost, ketuk titik tiga di kanan atas, lalu pilih \"Izinkan setelan terbatas\".");

        buildPermRows((LinearLayout) header.findViewById(R.id.permList));
        buildSwitchRows((LinearLayout) header.findViewById(R.id.switchList));

        adapter = new GameAdapter(this, items);
        adapter.selectedPkg = selectedPkg;
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Object o = parent.getItemAtPosition(pos);
                if (o instanceof GameItem) {
                    selectedPkg = ((GameItem) o).pkg;
                    prefs.edit().putString("game_pkg", selectedPkg).apply();
                    adapter.selectedPkg = selectedPkg;
                    adapter.notifyDataSetChanged();
                    updateBottom();
                }
            }
        });

        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (GameService.running) {
                    stopService(new Intent(MainActivity.this, GameService.class));
                    updateBottom();
                } else {
                    startMode();
                }
            }
        });

        loadApps();

        // Pertama kali dibuka: tampilkan panduan Shizuku/root.
        if (!prefs.getBoolean("guide_seen", false)) {
            prefs.edit().putBoolean("guide_seen", true).apply();
            openGuide();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermUi();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        updatePermUi();
    }

    // ---------------------------------------------------------------- baris izin

    private void buildPermRows(LinearLayout parent) {
        addPerm(parent, "Jendela mengambang", R.drawable.ic_window, 0xFF0A84FF,
                this::canOverlay, this::openOverlaySettings);

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
                this::hasNotifPerm, () -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                });

        addPerm(parent, "Tanpa batasan baterai", R.drawable.ic_battery, 0xFF34C759,
                this::ignoresBatteryOpt, this::requestIgnoreBattery);

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

    // ---------------------------------------------------------------- baris pengaturan

    private void buildSwitchRows(LinearLayout parent) {
        swKill = addSwitch(parent, "Tutup semua aplikasi latar belakang", R.drawable.ic_sparkle, 0xFFFF9F0A, "kill", true);
        swNotif = addSwitch(parent, "Hapus semua notifikasi yang masuk", R.drawable.ic_bell, 0xFFFF453A, "notif", true);
        swDnd = addSwitch(parent, "Hening total (Jangan Ganggu)", R.drawable.ic_moon, 0xFF5E5CE6, "dnd", true);
        swOverlay = addSwitch(parent, "Jendela mengambang saat bermain", R.drawable.ic_window, 0xFF0A84FF, "overlay", true);
        swFreeze = addSwitch(parent, "Bekukan, bukan sekadar tutup (Shizuku)", R.drawable.ic_sparkle, 0xFF64D2FF, "freeze", true);
        swPerf = addSwitch(parent, "Mode performa: refresh rate maks (Shizuku)", R.drawable.ic_bolt, 0xFF30D158, "perf", true);
        swFreeform = addSwitch(parent, "Buka game di jendela kecil", R.drawable.ic_gamepad, 0xFF32ADE6, "freeform", false);
    }

    private Switch addSwitch(LinearLayout parent, String title, int icon, int tint, final String key, boolean def) {
        View row = getLayoutInflater().inflate(R.layout.row_switch, parent, false);
        row.findViewById(R.id.swDivider).setVisibility(parent.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.swTile).setBackgroundTintList(ColorStateList.valueOf(tint));
        ((ImageView) row.findViewById(R.id.swIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.swTitle)).setText(title);

        final Switch sw = row.findViewById(R.id.swSwitch);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
            }
        });
        row.findViewById(R.id.swRow).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sw.toggle(); }
        });
        parent.addView(row);
        return sw;
    }

    // ---------------------------------------------------------------- daftar aplikasi

    private void loadApps() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<GameItem> found = queryApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        items.clear();
                        items.addAll(found);
                        adapter.notifyDataSetChanged();
                        tvCount.setText(items.size() + " aplikasi");
                        updateBottom();
                    }
                });
            }
        }).start();
    }

    private List<GameItem> queryApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        List<GameItem> out = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean game = detectGame(pkg, ri.activityInfo.applicationInfo);
            out.add(new GameItem(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm), game));
        }
        Collections.sort(out, new Comparator<GameItem>() {
            @Override public int compare(GameItem a, GameItem b) {
                if (a.isGame != b.isGame) return a.isGame ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private boolean detectGame(String pkg, ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT >= 26 && ai != null && ai.category == ApplicationInfo.CATEGORY_GAME) {
            return true;
        }
        String p = pkg.toLowerCase(Locale.US);
        for (String h : GAME_HINTS) {
            if (p.contains(h)) return true;
        }
        return false;
    }

    private GameItem selectedItem() {
        if (selectedPkg == null) return null;
        for (GameItem g : items) {
            if (g.pkg.equals(selectedPkg)) return g;
        }
        return null;
    }

    // ---------------------------------------------------------------- mode game

    private void startMode() {
        final GameItem game = selectedItem();
        if (game == null) {
            toast("Pilih game dulu dari daftar");
            return;
        }
        final boolean kill = swKill.isChecked();
        final boolean notif = swNotif.isChecked();
        final boolean dnd = swDnd.isChecked();
        final boolean overlay = swOverlay.isChecked();
        final boolean freeze = swFreeze.isChecked();
        final boolean perf = swPerf.isChecked();

        if (overlay && !canOverlay()) {
            toast("Izinkan \"Tampil di atas aplikasi lain\" lalu tekan Mulai lagi");
            openOverlaySettings();
            return;
        }
        if (dnd && Build.VERSION.SDK_INT >= 23 && !GameBooster.hasDndAccess(this)) {
            toast("Beri akses Jangan Ganggu ke GameBoost lalu tekan Mulai lagi");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            return;
        }
        if (notif && !GameNotifService.isEnabled(this)) {
            toast("Nyalakan \"Akses notifikasi\" untuk GameBoost lalu tekan Mulai lagi");
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception ignored) {
            }
            return;
        }

        final boolean shizuku = ShizukuHelper.ready();
        final boolean freezeOn = freeze && shizuku;
        if ((freeze || perf) && !shizuku) {
            toast("Shizuku belum aktif, memakai cara biasa. Buka \"Panduan Shizuku & root\" di bagian Izin.");
        }

        btnMain.setEnabled(false);
        btnMain.setText("Membersihkan…");

        new Thread(new Runnable() {
            @Override public void run() {
                final GameBooster.Result r = kill ? GameBooster.boost(MainActivity.this, game.pkg, freezeOn) : null;
                if (perf && shizuku) GameBooster.enablePerf(MainActivity.this, game.pkg);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (dnd) GameBooster.enableDnd(MainActivity.this);

                        Intent s = new Intent(MainActivity.this, GameService.class);
                        s.putExtra(EXTRA_GAME, game.pkg);
                        s.putExtra(EXTRA_OVERLAY, overlay);
                        s.putExtra(EXTRA_KILL, kill);
                        s.putExtra(EXTRA_NOTIF, notif);
                        s.putExtra(EXTRA_FREEZE, freezeOn);
                        if (r != null) {
                            s.putExtra(EXTRA_FREED, r.freedMb);
                            s.putExtra(EXTRA_REQUESTED, r.requested);
                        }
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
                        else startService(s);

                        if (r != null) {
                            String what = r.frozen > 0
                                    ? String.format(Locale.US, "%d aplikasi dibekukan", r.frozen)
                                    : String.format(Locale.US, "%d aplikasi dihentikan", r.requested);
                            toast(String.format(Locale.US, "%s  •  RAM +%d MB  •  cara: %s",
                                    what, r.freedMb, r.method));
                        }
                        btnMain.setEnabled(true);
                        launchGame(game.pkg);
                        updateBottom();
                    }
                });
            }
        }).start();
    }

    private void launchGame(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("Game tidak bisa dibuka");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle opts = null;
        if (swFreeform.isChecked() && Build.VERSION.SDK_INT >= 24) {
            // Eksperimental: hanya berjalan di HP yang mendukung jendela freeform.
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            Rect r = new Rect(w / 20, h / 8, w - w / 20, h / 8 + (int) (h * 0.55f));
            opts = ActivityOptions.makeBasic().setLaunchBounds(r).toBundle();
        }
        try {
            startActivity(i, opts);
        } catch (Exception e) {
            toast("Gagal membuka game");
        }
    }

    // ---------------------------------------------------------------- UI

    private void updateStatus() {
        SysInfo.Ram r = SysInfo.ram(this);
        SysInfo.Batt b = SysInfo.battery(this);

        int rc = r.usedPct >= 88 ? R.color.danger : (r.usedPct >= 75 ? R.color.warn : R.color.ok);
        ringRam.setValue(r.usedPct, SysInfo.color(this, rc));
        tvRamDetail.setText(String.format(Locale.US, "%d / %d MB", r.usedMb, r.totalMb));

        tvTemp.setText(String.format(Locale.US, "%.1f°C", b.tempC));
        tvTemp.setTextColor(SysInfo.color(this,
                b.tempC >= 43f ? R.color.danger : (b.tempC >= 40f ? R.color.warn : R.color.text)));
        tvBatt.setText(String.format(Locale.US, "%d%%%s", b.level, b.charging ? " ⚡" : ""));
    }

    private void updateBottom() {
        GameItem g = selectedItem();
        if (GameService.running) {
            btnMain.setText("Hentikan Mode Game");
            btnMain.setBackgroundResource(R.drawable.bg_button_stop);
            tvSelected.setText("Mode game aktif" + (g != null ? "  •  " + g.label : ""));
        } else {
            btnMain.setText("Mulai Mode Game");
            btnMain.setBackgroundResource(R.drawable.bg_button);
            tvSelected.setText(g != null ? g.label : "Pilih game dari daftar di atas");
        }
    }

    /** Nama pembuat: gradasi warna dan muncul perlahan seperti tanda tangan. */
    private void setupCreator(View header) {
        final TextView name = header.findViewById(R.id.tvCreatorName);
        View wrap = header.findViewById(R.id.creatorWrap);
        name.post(new Runnable() {
            @Override public void run() {
                float w = Math.max(1f, name.getPaint().measureText(name.getText().toString()));
                name.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                        new int[]{0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F}, null,
                        Shader.TileMode.CLAMP));
                name.invalidate();
            }
        });
        float d = getResources().getDisplayMetrics().density;
        wrap.setAlpha(0f);
        wrap.setTranslationY(8f * d);
        wrap.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(850).start();
    }

    private void openGuide() {
        startActivity(new Intent(this, GuideActivity.class));
    }

    private void updateRestore() {
        int n = GameBooster.pendingCount(this);
        cardRestore.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) tvRestore.setText("Pulihkan semua aplikasi (" + n + ")");
    }

    private void restoreAll() {
        if (!ShizukuHelper.ready()) {
            toast("Nyalakan Shizuku dulu, lalu ketuk lagi");
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final int n = GameBooster.restoreSession(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(n >= 0 ? "Dipulihkan: " + n + " aplikasi" : "Gagal memulihkan, coba lagi");
                        updateRestore();
                    }
                });
            }
        }).start();
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private boolean hasNotifPerm() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ignoresBatteryOpt() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
