package com.gameboost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

/**
 * Layanan mode game. Di tepi layar ada gagang tipis; geser ke dalam dan panel bergaya iOS
 * meluncur keluar dengan grafik CPU, GPU, RAM, internet, serta ikon baterai. Geser panel ke arah
 * tepi (atau ketuk di luar panel) untuk menyimpannya lagi. Saat berhenti, Jangan Ganggu dikembalikan.
 */
public class GameService extends Service {

    public static volatile boolean running = false;
    /** true jika semua notifikasi yang masuk harus dihapus (dibaca oleh GameNotifService). */
    public static volatile boolean blockNotifs = false;

    static final String ACTION_STOP = "com.gameboost.app.action.STOP";
    private static final String CHANNEL = "gameboost_mode";
    private static final int NOTIF_ID = 7;
    private static final int CARD_W_DP = 300;
    private static final long AUTO_HIDE_MS = 12000;
    private static final long FIRST_HIDE_MS = 6000;
    private static final long SAMPLE_MS = 2000;
    private static final long KILL_INTERVAL_MS = 20000;

    /** Satu kotak grafik di panel. */
    private static final class Metric {
        TextView value, sub;
        SparkView spark;
    }

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View root, handle, card, btnClean;
    private ImageView icCollapse;
    private BatteryView battery, handleBattery;
    private TextView handleBattText;
    private boolean battReceiverOn = false;
    private TextView tvBatt, tvTemp, tvHint, tvToast, tvClean;
    private final Metric[] metrics = new Metric[4];     // CPU, GPU, RAM, Internet
    private static final int M_CPU = 0, M_GPU = 1, M_RAM = 2, M_NET = 3;

    private boolean expanded = false;
    private boolean onRight = true;
    private boolean cleaning = false;
    private int slop;
    private String gamePkg = "";
    private String netType = "";

    private HandlerThread sampleThread;
    private Handler sampleHandler;
    private SysStats stats;
    private volatile boolean sampling = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };
    /** Baterai berubah (turun/naik): ikon di gagang dan di panel ikut berubah saat itu juga. */
    private final BroadcastReceiver battReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateBattery();
        }
    };
    private final Runnable hideToast = new Runnable() {
        @Override public void run() {
            if (tvToast != null) tvToast.setVisibility(View.GONE);
        }
    };
    private final Runnable autoHide = new Runnable() {
        @Override public void run() {
            closePanel();
        }
    };
    private final Runnable sampleTask = new Runnable() {
        @Override public void run() {
            SysStats st = stats;
            if (!sampling || st == null) return;
            final SysStats.Snap snap = st.sample(getApplicationContext());
            handler.post(new Runnable() {
                @Override public void run() { applyStats(snap); }
            });
            Handler h = sampleHandler;
            if (sampling && h != null) h.postDelayed(this, SAMPLE_MS);
        }
    };

    // Penjaga: aplikasi latar belakang sering hidup lagi sendiri, jadi ditutup ulang berkala.
    private boolean killLoop = true;
    private boolean freezeMode = false;
    private volatile boolean killing = false;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (killLoop && !killing) {
                killing = true;
                final Context app = getApplicationContext();
                final String game = gamePkg;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            GameBooster.quickKill(app, game, freezeMode);
                            GameNotifService.clearAll();
                        } finally {
                            killing = false;
                        }
                    }
                }).start();
            }
            handler.postDelayed(this, KILL_INTERVAL_MS);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean overlay = true;
        long freed = 0;
        int requested = 0;
        if (intent != null) {
            String g = intent.getStringExtra(MainActivity.EXTRA_GAME);
            if (g != null) gamePkg = g;
            overlay = intent.getBooleanExtra(MainActivity.EXTRA_OVERLAY, true);
            freed = intent.getLongExtra(MainActivity.EXTRA_FREED, 0);
            requested = intent.getIntExtra(MainActivity.EXTRA_REQUESTED, 0);
            killLoop = intent.getBooleanExtra(MainActivity.EXTRA_KILL, true);
            blockNotifs = intent.getBooleanExtra(MainActivity.EXTRA_NOTIF, false);
            freezeMode = intent.getBooleanExtra(MainActivity.EXTRA_FREEZE, false);
        }

        // Android 14+ mewajibkan tipe layanan foreground.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        running = true;

        if (blockNotifs) GameNotifService.clearAll();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, KILL_INTERVAL_MS);

        if (overlay && canDraw() && root == null) {
            showPanel();
            if (root != null) openPanel(FIRST_HIDE_MS);   // tampil sebentar lalu masuk ke tepi
        }
        if (root != null && requested > 0) {
            flashToast(String.format(Locale.US, "✓ %d aplikasi dihentikan  •  RAM +%d MB",
                    requested, freed));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideToast);
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(watchdog);
        stopSampling();
        if (battReceiverOn) {
            try {
                unregisterReceiver(battReceiver);
            } catch (Exception ignored) {
            }
            battReceiverOn = false;
        }
        if (sampleThread != null) {
            sampleThread.quitSafely();
            sampleThread = null;
        }
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        running = false;
        blockNotifs = false;
        GameBooster.restoreDnd(this);
        // Pulihkan aplikasi yang dibekukan dan setelan performa (butuh Shizuku, jadi di thread lain).
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { GameBooster.restoreSession(appCtx); }
        }).start();
        stopForeground(true);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notifikasi

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Mode game",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        Intent stop = new Intent(this, GameService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Mode game aktif")
                .setContentText("Ketuk untuk membuka GameBoost")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Hentikan", stopPi);
        return b.build();
    }

    // ---------------------------------------------------------------- jendela mengambang

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private int[] screen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void applyLayout() {
        if (root == null) return;
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    private Metric addMetric(LinearLayout row, String label, int color, float fixedMax, float autoFloor) {
        View v = LayoutInflater.from(row.getContext()).inflate(R.layout.cell_metric, row, false);
        Metric m = new Metric();
        ((TextView) v.findViewById(R.id.cellLabel)).setText(label);
        m.value = v.findViewById(R.id.cellValue);
        m.sub = v.findViewById(R.id.cellSub);
        m.spark = v.findViewById(R.id.cellSpark);
        m.spark.setColor(color);
        m.spark.setFixedMax(fixedMax, autoFloor);
        row.addView(v);
        return m;
    }

    private void showPanel() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ContextWrapper themed = new ContextThemeWrapper(this, R.style.Theme_GameBoost);
        root = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null);

        handle = root.findViewById(R.id.handle);
        card = root.findViewById(R.id.card);
        icCollapse = root.findViewById(R.id.icCollapse);
        battery = root.findViewById(R.id.battery);
        handleBattery = root.findViewById(R.id.handleBattery);
        handleBattText = root.findViewById(R.id.handleBattText);
        tvBatt = root.findViewById(R.id.tvBatt);
        tvTemp = root.findViewById(R.id.tvTemp);
        tvHint = root.findViewById(R.id.tvHint);
        tvToast = root.findViewById(R.id.tvToast);
        tvClean = root.findViewById(R.id.tvClean);
        btnClean = root.findViewById(R.id.btnClean);
        slop = ViewConfiguration.get(this).getScaledTouchSlop();
        onRight = !Prefs.flag(this, "panel_left");

        LinearLayout row1 = root.findViewById(R.id.gridRow1);
        LinearLayout row2 = root.findViewById(R.id.gridRow2);
        metrics[M_CPU] = addMetric(row1, "CPU", 0xFF0A84FF, 100f, 1f);
        metrics[M_GPU] = addMetric(row1, "GPU", 0xFFBF5AF2, 100f, 1f);
        metrics[M_RAM] = addMetric(row2, "RAM", 0xFF30D158, 100f, 1f);
        metrics[M_NET] = addMetric(row2, "INTERNET", 0xFFFF9F0A, 0f, 64f);   // skala otomatis (KB/s)

        // Panah tutup menunjuk ke arah tepi tempat panel menempel.
        icCollapse.setRotation(onRight ? 0f : 180f);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,   // layar tidak redup saat main
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (onRight ? Gravity.END : Gravity.START);
        lp.x = 0;
        lp.y = Prefs.get(this).getInt("panel_y", dp(96));

        handle.setOnTouchListener(new HandleTouch());
        card.setOnTouchListener(new CardTouch());
        // Ketukan di luar panel (masuk ke game) menyimpan panel kembali.
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && expanded) closePanel();
                return false;
            }
        });

        root.findViewById(R.id.btnCollapse).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePanel(); }
        });
        root.findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cleanNow(); }
        });

        card.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
        handle.setAlpha(0.75f);
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }

        if (!battReceiverOn) {
            registerReceiver(battReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            battReceiverOn = true;
        }
        updateBattery();

        // Gagang di tepi layar tidak boleh dikalahkan oleh gestur "kembali" sistem (Android 10+).
        if (Build.VERSION.SDK_INT >= 29) {
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                     int ol, int ot, int orr, int ob) {
                    v.setSystemGestureExclusionRects(
                            Collections.singletonList(new Rect(0, 0, r - l, b - t)));
                }
            });
        }
    }

    /** Geser gagang ke dalam untuk membuka panel; geser naik/turun untuk memindahkan posisinya. */
    private final class HandleTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startY;
        private boolean vertical, opened;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startY = lp.y;
                    vertical = false;
                    opened = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    float inward = onRight ? -dx : dx;
                    if (!vertical && !opened) {
                        if (inward > slop * 1.5f && inward > Math.abs(dy)) {
                            opened = true;
                            openPanel(AUTO_HIDE_MS);
                        } else if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(inward)) {
                            vertical = true;
                        }
                    }
                    if (vertical) {
                        lp.y = startY + Math.round(dy);
                        clampY();
                        applyLayout();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (vertical) {
                        Prefs.get(GameService.this).edit().putInt("panel_y", lp.y).apply();
                    } else if (!opened) {
                        openPanel(AUTO_HIDE_MS);          // ketukan biasa juga membuka
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Geser panel ke arah tepi untuk menyimpannya; berhenti di tengah akan kembali terbuka. */
    private final class CardTouch implements View.OnTouchListener {
        private float downX;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            float w = dp(CARD_W_DP);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    handler.removeCallbacks(autoHide);
                    card.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float out = onRight ? e.getRawX() - downX : downX - e.getRawX();
                    card.setTranslationX((onRight ? 1 : -1) * Math.max(0f, Math.min(out, w)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float out = onRight ? e.getRawX() - downX : downX - e.getRawX();
                    if (out > w * 0.3f) {
                        closePanel();
                    } else {
                        card.animate().translationX(0f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        scheduleAutoHide(AUTO_HIDE_MS);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    private void clampY() {
        if (root == null) return;
        int[] s = screen();
        int h = root.getHeight();
        lp.y = Math.max(0, Math.min(lp.y, Math.max(0, s[1] - h)));
    }

    /** Panel meluncur dari tepi layar ke dalam. */
    private void openPanel(long hideAfterMs) {
        if (root == null) return;
        if (expanded) {
            scheduleAutoHide(hideAfterMs);
            return;
        }
        expanded = true;
        float w = dp(CARD_W_DP);
        handle.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        card.setTranslationX(onRight ? w : -w);
        card.setAlpha(0.4f);
        card.animate().translationX(0f).alpha(1f).setDuration(300)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        root.post(new Runnable() {
            @Override public void run() {
                clampY();
                applyLayout();
            }
        });
        refresh();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1500);
        startSampling();
        scheduleAutoHide(hideAfterMs);
    }

    /** Panel meluncur kembali ke tepi layar dan tinggal gagang tipisnya. */
    private void closePanel() {
        if (root == null || !expanded) return;
        expanded = false;
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(tick);
        stopSampling();
        float w = dp(CARD_W_DP);
        card.animate().cancel();
        card.animate().translationX(onRight ? w : -w).alpha(0f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (root == null || expanded) return;
                        card.setVisibility(View.GONE);
                        card.setTranslationX(0f);
                        handle.setVisibility(View.VISIBLE);
                        handle.setAlpha(0f);
                        handle.animate().alpha(0.75f).setDuration(220).start();
                    }
                }).start();
    }

    private void scheduleAutoHide(long ms) {
        handler.removeCallbacks(autoHide);
        handler.postDelayed(autoHide, ms);
    }

    // ---------------------------------------------------------------- data langsung

    private void startSampling() {
        if (sampleThread == null) {
            sampleThread = new HandlerThread("gb-sampler");
            sampleThread.start();
            sampleHandler = new Handler(sampleThread.getLooper());
        }
        stats = new SysStats();                 // sampel baru: CPU dan internet dihitung dari selisih
        for (Metric m : metrics) {
            if (m != null) m.spark.clear();
        }
        sampling = true;
        sampleHandler.removeCallbacks(sampleTask);
        sampleHandler.post(sampleTask);
    }

    private void stopSampling() {
        sampling = false;
        if (sampleHandler != null) sampleHandler.removeCallbacks(sampleTask);
    }

    /** Dipanggil di thread utama dengan hasil pembacaan terbaru. */
    private void applyStats(SysStats.Snap s) {
        if (root == null || !expanded) return;

        Metric cpu = metrics[M_CPU];
        if (s.cpu >= 0) {
            cpu.value.setText(s.cpu + "%");
            cpu.spark.push(s.cpu);
            cpu.sub.setText(s.cpuByFreq ? "perkiraan dari frekuensi" : "beban prosesor");
        } else {
            cpu.value.setText("—");
            cpu.sub.setText("tidak terbaca");
        }

        Metric gpu = metrics[M_GPU];
        if (s.gpu >= 0) {
            gpu.value.setText(s.gpu + "%");
            gpu.spark.push(s.gpu);
            gpu.sub.setText("beban grafis");
        } else {
            gpu.value.setText("—");
            gpu.sub.setText(ShizukuHelper.ready() ? "tidak tersedia di HP ini" : "butuh Shizuku");
        }

        Metric net = metrics[M_NET];
        if (s.rxBps >= 0) {
            net.value.setText("↓ " + SysStats.speed(s.rxBps));
            net.spark.push(s.rxBps / 1024f);
            net.sub.setText("↑ " + SysStats.speed(s.txBps) + (netType.isEmpty() ? "" : "  •  " + netType));
        } else {
            net.value.setText("—");
            net.sub.setText(netType);
        }
    }

    private void cleanNow() {
        if (cleaning) return;
        cleaning = true;
        tvClean.setText("Membersihkan…");
        handler.removeCallbacks(autoHide);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final GameBooster.Result r = GameBooster.boost(app, gamePkg, freezeMode);
                handler.post(new Runnable() {
                    @Override public void run() {
                        cleaning = false;
                        if (root == null) return;
                        tvClean.setText("Bersihkan");
                        flashToast(String.format(Locale.US, "✓ RAM +%d MB  •  %d aplikasi dihentikan",
                                r.freedMb, r.requested));
                        if (expanded) scheduleAutoHide(AUTO_HIDE_MS);
                    }
                });
            }
        }).start();
    }

    private void flashToast(String msg) {
        if (tvToast == null) return;
        tvToast.setText(msg);
        tvToast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 4500);
    }

    /** Samakan semua ikon baterai (gagang + panel) dengan level baterai asli. */
    private void updateBattery() {
        if (root == null) return;
        SysInfo.Batt b = SysInfo.battery(this);
        handleBattery.setLevel(b.level, b.charging);
        handleBattText.setText(b.level + "%");
        battery.setLevel(b.level, b.charging);
        tvBatt.setText(String.format(Locale.US, "%d%%", b.level));
    }

    /** Bagian yang ringan dan dibaca di thread utama: baterai, suhu, RAM, jenis jaringan. */
    private void refresh() {
        if (root == null || !expanded) return;
        SysInfo.Ram r = SysInfo.ram(this);
        SysInfo.Batt b = SysInfo.battery(this);
        netType = SysStats.networkType(this);

        battery.setLevel(b.level, b.charging);
        tvBatt.setText(String.format(Locale.US, "%d%%", b.level));

        Metric ram = metrics[M_RAM];
        ram.value.setText(r.usedPct + "%");
        ram.spark.push(r.usedPct);
        ram.sub.setText(String.format(Locale.US, "%d / %d MB", r.usedMb, r.totalMb));

        tvTemp.setText(String.format(Locale.US, "%.1f°C", b.tempC));
        tvTemp.setTextColor(SysInfo.color(this, b.tempC >= 43f ? R.color.danger
                : (b.tempC >= 40f ? R.color.warn : R.color.text)));
        tvHint.setText(b.tempC >= 43f ? "Panas, turunkan grafis sebentar"
                : (b.tempC >= 40f ? "Mulai hangat, pantau terus" : "Performa stabil"));

        // Jaga posisi tetap di dalam layar jika layar diputar.
        int oy = lp.y;
        clampY();
        if (oy != lp.y) applyLayout();
    }
}
