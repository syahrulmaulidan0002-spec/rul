package com.gameboost.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Beranda game: game terpilih, deretan "Game saya", kondisi HP, dan tombol mulai mode game. */
public class MainActivity extends Activity {

    static final String EXTRA_GAME = "game";
    static final String EXTRA_OVERLAY = "overlay";
    static final String EXTRA_FREED = "freed";
    static final String EXTRA_REQUESTED = "requested";
    static final String EXTRA_KILL = "kill";
    static final String EXTRA_NOTIF = "notif";
    static final String EXTRA_FREEZE = "freeze";
    static final String EXTRA_NETFOCUS = "netfocus";

    private static final String KEY_MY_GAMES = "my_games";


    private SharedPreferences prefs;
    private final List<GameItem> all = new ArrayList<>();      // semua aplikasi yang bisa dibuka
    private final List<GameItem> mine = new ArrayList<>();     // tampil di "Game saya"
    private final List<GameItem> others = new ArrayList<>();   // daftar aplikasi lain
    private GameAdapter adapter;
    private String selectedPkg;
    private String heroPkg = null; // null = belum pernah digambar
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListView list;
    private RingView ringRam;
    private BatteryView mainBattery;
    private ImageView heroIcon;
    private LinearLayout gamesRow;
    private View cardRestore;
    private TextView tvRamDetail, tvTemp, tvBatt, tvGraphics, tvCount, tvMineCount, tvSelected;
    private TextView heroName, heroSub, heroStatus, tvNoGames, tvShizuku, tvRestore;
    private TextView heroScore, tvAuto, tvSessionTitle, tvSessionBody;
    private View cardSession;
    private Button btnMain;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateStatus();
            updateBottom();
            updateHeroStatus();
            updateShizuku();
            updateAuto();
            updateSession();
            updateRestore();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
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
        mainBattery = header.findViewById(R.id.mainBattery);
        tvGraphics = header.findViewById(R.id.tvGraphics);
        tvCount = header.findViewById(R.id.tvCount);
        tvMineCount = header.findViewById(R.id.tvMineCount);
        heroIcon = header.findViewById(R.id.heroIcon);
        heroName = header.findViewById(R.id.heroName);
        heroSub = header.findViewById(R.id.heroSub);
        heroStatus = header.findViewById(R.id.heroStatus);
        gamesRow = header.findViewById(R.id.gamesRow);
        tvNoGames = header.findViewById(R.id.tvNoGames);
        tvShizuku = header.findViewById(R.id.tvShizuku);
        heroScore = header.findViewById(R.id.heroScore);
        tvAuto = header.findViewById(R.id.tvAuto);
        cardSession = header.findViewById(R.id.cardSession);
        tvSessionTitle = header.findViewById(R.id.tvSessionTitle);
        tvSessionBody = header.findViewById(R.id.tvSessionBody);
        header.findViewById(R.id.rowAuto).setOnClickListener(v -> openSettings());
        cardRestore = header.findViewById(R.id.cardRestore);
        tvRestore = header.findViewById(R.id.tvRestore);

        tvGraphics.setText(SysInfo.advice(this));
        setupCreator(header);

        header.findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        heroStatus.setOnClickListener(v -> openSettings());
        header.findViewById(R.id.rowShizuku).setOnClickListener(v -> openGuide());
        header.findViewById(R.id.rowRestore).setOnClickListener(v -> restoreAll());

        adapter = new GameAdapter(this, others);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Object o = parent.getItemAtPosition(pos);
                if (o instanceof GameItem) {
                    // Aplikasi dari daftar ini ditambahkan ke "Game saya" lalu dipilih.
                    String pkg = ((GameItem) o).pkg;
                    Set<String> my = myGames();
                    my.add(pkg);
                    prefs.edit().putStringSet(KEY_MY_GAMES, my).apply();
                    select(pkg);
                }
            }
        });

        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (GameService.running) {
                    startService(new Intent(MainActivity.this, GameService.class)
                            .setAction(GameService.ACTION_STOP));
                    updateBottom();
                } else {
                    startMode();
                }
            }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameService.syncWatch(this);
        updateHeroStatus();
        updateShizuku();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ---------------------------------------------------------------- daftar aplikasi

    private Set<String> myGames() {
        return new HashSet<>(prefs.getStringSet(KEY_MY_GAMES, Collections.<String>emptySet()));
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<GameItem> found = queryApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(found);
                        rebuild();
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
        return GameDetect.detect(pkg, ai);
    }

    /** Bagi aplikasi menjadi "Game saya" dan "Aplikasi lain", lalu segarkan tampilan. */
    private void rebuild() {
        Set<String> my = myGames();
        mine.clear();
        others.clear();
        for (GameItem g : all) {
            if (g.isGame || my.contains(g.pkg) || g.pkg.equals(selectedPkg)) mine.add(g);
            else others.add(g);
        }
        adapter.selectedPkg = selectedPkg;
        adapter.notifyDataSetChanged();
        tvCount.setText(others.size() + " aplikasi");
        tvMineCount.setText(mine.size() + " game");
        fillCarousel();
        updateHero();
        updateBottom();
    }

    private void fillCarousel() {
        gamesRow.removeAllViews();
        for (final GameItem g : mine) {
            View v = getLayoutInflater().inflate(R.layout.item_game_card, gamesRow, false);
            ((ImageView) v.findViewById(R.id.cardIcon)).setImageDrawable(g.icon);
            ((TextView) v.findViewById(R.id.cardName)).setText(g.label);
            v.findViewById(R.id.cardBox).setBackgroundResource(g.pkg.equals(selectedPkg)
                    ? R.drawable.bg_game_card_sel : R.drawable.bg_game_card);
            v.setOnClickListener(x -> select(g.pkg));
            v.setOnLongClickListener(x -> {
                removeFromMine(g);
                return true;
            });
            gamesRow.addView(v);
        }
        tvNoGames.setVisibility(mine.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void select(String pkg) {
        selectedPkg = pkg;
        prefs.edit().putString("game_pkg", pkg).apply();
        GameWidget.update(this);
        rebuild();
    }

    private void removeFromMine(GameItem g) {
        if (g.isGame) {
            toast("Game ini terdeteksi otomatis, jadi tidak bisa dihapus dari daftar");
            return;
        }
        Set<String> my = myGames();
        my.remove(g.pkg);
        SharedPreferences.Editor ed = prefs.edit().putStringSet(KEY_MY_GAMES, my);
        if (g.pkg.equals(selectedPkg)) {
            selectedPkg = null;
            ed.remove("game_pkg");
        }
        ed.apply();
        rebuild();
        toast("Dihapus dari Game saya");
    }

    private GameItem selectedItem() {
        if (selectedPkg == null) return null;
        for (GameItem g : all) {
            if (g.pkg.equals(selectedPkg)) return g;
        }
        return null;
    }

    // ---------------------------------------------------------------- mode game

    private void startMode() {
        final GameItem game = selectedItem();
        if (game == null) {
            toast("Pilih game dulu dari Game saya");
            return;
        }
        String missing = missingPermissions();
        if (missing != null) {
            toast("Atur dulu: " + missing);
            openSettings();
            return;
        }
        if ((Prefs.flag(this, "freeze") || Prefs.flag(this, "perf") || Prefs.flag(this, "netfocus"))
                && !ShizukuHelper.ready()) {
            toast("Shizuku belum aktif, memakai cara biasa. Ketuk kartu Shizuku untuk panduannya.");
        }

        if (Prefs.flag(this, "anim")) {
            // Animasi masuk game: persiapan dijalankan di layar itu, lalu game terbuka.
            startActivity(new Intent(this, LaunchActivity.class).putExtra(LaunchActivity.EXTRA_PKG, game.pkg));
            return;
        }

        btnMain.setEnabled(false);
        btnMain.setText("Membersihkan…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SessionRunner.run(app, game.pkg, null, true);
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnMain.setEnabled(true);
                        if (!GameLauncher.launch(MainActivity.this, game.pkg)) toast("Game tidak bisa dibuka");
                        updateBottom();
                    }
                });
            }
        }).start();
    }

    /** Daftar izin yang masih kurang untuk setelan sekarang, atau null jika sudah lengkap. */
    private String missingPermissions() {
        return PermHelper.missing(this);
    }

    // ---------------------------------------------------------------- tampilan

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

    /** Kartu game terpilih di bagian atas beranda. */
    private void updateHero() {
        GameItem g = selectedItem();
        String pkg = g != null ? g.pkg : "";
        if (!pkg.equals(heroPkg)) {
            heroPkg = pkg;
            if (g != null) {
                heroIcon.setPadding(0, 0, 0, 0);
                heroIcon.clearColorFilter();
                heroIcon.setImageDrawable(g.icon);
            } else {
                int pad = Math.round(16 * getResources().getDisplayMetrics().density);
                heroIcon.setPadding(pad, pad, pad, pad);
                heroIcon.setColorFilter(0xFF8E8E93);
                heroIcon.setImageResource(R.drawable.ic_gamepad);
            }
        }
        heroName.setText(g != null ? g.label : "Pilih game");
        heroSub.setText(g != null ? g.pkg : "Ketuk game di bawah");
        updateHeroStatus();
    }

    private void updateHeroStatus() {
        if (heroStatus == null) return;
        if (GameService.running) {
            heroStatus.setText("Mode game sedang aktif");
            heroStatus.setTextColor(SysInfo.color(this, R.color.ok));
            Icons.start(heroStatus, R.drawable.ic_dot, 12, SysInfo.color(this, R.color.ok));
            return;
        }
        String missing = missingPermissions();
        if (missing != null) {
            heroStatus.setText("Perlu diatur: " + missing + "  •  ketuk untuk membuka Pengaturan");
            heroStatus.setTextColor(SysInfo.color(this, R.color.warn));
            Icons.clear(heroStatus);
        } else {
            heroStatus.setText("Siap dimainkan");
            heroStatus.setTextColor(SysInfo.color(this, R.color.ok));
            Icons.start(heroStatus, R.drawable.ic_check, 14, SysInfo.color(this, R.color.ok));
        }
    }

    private void updateAuto() {
        if (tvAuto == null) return;
        if (!Prefs.flag(this, "auto")) {
            tvAuto.setText("Mati");
            tvAuto.setTextColor(SysInfo.color(this, R.color.muted));
        } else if (!PermHelper.hasUsageAccess(this)) {
            tvAuto.setText("Perlu izin");
            tvAuto.setTextColor(SysInfo.color(this, R.color.warn));
        } else {
            tvAuto.setText(GameService.watching ? "Aktif" : "Menyalakan…");
            tvAuto.setTextColor(SysInfo.color(this, R.color.ok));
        }
    }

    private void updateSession() {
        if (cardSession == null) return;
        String t = SessionStats.title(this);
        if (t == null) {
            cardSession.setVisibility(View.GONE);
            return;
        }
        cardSession.setVisibility(View.VISIBLE);
        tvSessionTitle.setText(t);
        tvSessionBody.setText(SessionStats.body(this));
    }

    private void updateShizuku() {
        if (tvShizuku == null) return;
        String text;
        int color;
        if (ShizukuHelper.granted()) {
            text = ShizukuHelper.isRoot() ? "Aktif (root)" : "Aktif";
            color = R.color.ok;
        } else if (ShizukuHelper.running()) {
            text = "Perlu izin";
            color = R.color.warn;
        } else if (ShizukuHelper.installed(this)) {
            text = "Belum menyala";
            color = R.color.warn;
        } else {
            text = "Belum terpasang";
            color = R.color.muted;
        }
        tvShizuku.setText(text);
        tvShizuku.setTextColor(SysInfo.color(this, color));
    }

    private void updateStatus() {
        SysInfo.Ram r = SysInfo.ram(this);
        SysInfo.Batt b = SysInfo.battery(this);

        int rc = r.usedPct >= 88 ? R.color.danger : (r.usedPct >= 75 ? R.color.warn : R.color.ok);
        ringRam.setValue(r.usedPct, SysInfo.color(this, rc));
        tvRamDetail.setText(String.format(Locale.US, "%d / %d MB", r.usedMb, r.totalMb));

        tvTemp.setText(String.format(Locale.US, "%.1f°C", b.tempC));
        tvTemp.setTextColor(SysInfo.color(this,
                b.tempC >= 43f ? R.color.danger : (b.tempC >= 40f ? R.color.warn : R.color.text)));
        tvBatt.setText(String.format(Locale.US, "%d%%", b.level));
        if (b.charging) Icons.end(tvBatt, R.drawable.ic_bolt, 13, SysInfo.color(this, R.color.ok));
        else Icons.clear(tvBatt);
        ReadinessScore.Result rs = ReadinessScore.compute(this);
        heroScore.setText("Kesiapan " + rs.score + "/100  •  " + rs.tip);
        heroScore.setTextColor(SysInfo.color(this, rs.colorRes));
        mainBattery.setLevel(b.level, b.charging);
    }

    private void updateBottom() {
        GameItem g = selectedItem();
        if (GameService.running) {
            btnMain.setText("Hentikan Mode Game");
            btnMain.setBackgroundResource(R.drawable.bg_button_stop);
            tvSelected.setText("Mode game aktif" + (g != null ? "  •  " + g.label : ""));
        } else {
            btnMain.setText(g != null ? "Main " + g.label : "Mulai Mode Game");
            btnMain.setBackgroundResource(R.drawable.bg_button);
            tvSelected.setText(g != null ? "Semua aplikasi lain akan dihentikan atau dibekukan"
                    : "Pilih game dari Game saya");
        }
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
        final Context app = getApplicationContext();
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

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Halaman panduan langkah demi langkah Shizuku; hanya terbuka saat diketuk. */
    private void openGuide() {
        startActivity(new Intent(this, GuideActivity.class));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
