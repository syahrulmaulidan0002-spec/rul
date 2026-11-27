package com.gameboost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Layanan mode game. Di tepi layar ada gagang tipis; geser ke dalam dan panel bergaya iOS
 * meluncur keluar dengan grafik CPU, GPU, RAM, internet, serta ikon baterai. Geser panel ke arah
 * tepi (atau ketuk di luar panel) untuk menyimpannya lagi. Saat berhenti, Jangan Ganggu dikembalikan.
 */
public class GameService extends Service {

    /** true selama mode game aktif. */
    public static volatile boolean running = false;
    /** true selama layanan siaga (aktif otomatis) berjalan. */
    public static volatile boolean watching = false;
    /** true jika semua notifikasi yang masuk harus dihapus (dibaca oleh GameNotifService). */
    public static volatile boolean blockNotifs = false;

    static final String ACTION_STOP = "com.gameboost.app.action.STOP";
    static final String ACTION_WATCH = "com.gameboost.app.action.WATCH";
    static final String ACTION_UNWATCH = "com.gameboost.app.action.UNWATCH";
    private static final String CHANNEL = "gameboost_mode";
    private static final String CHANNEL_WATCH = "gameboost_watch";
    private static final String CHANNEL_SUMMARY = "gameboost_summary";
    private static final String CHANNEL_CAPTURE = "gameboost_capture";
    private static final int NOTIF_ID = 7;
    private static final int NOTIF_SUMMARY_ID = 8;
    private static final int NOTIF_BREAK_ID = 9;
    private static final int NOTIF_SHOT_ID = 10;   // 10 = screenshot, 11 = rekaman
    private static final int CARD_W_DP = 300;
    private static final long AUTO_HIDE_MS = 12000;
    private static final long FIRST_HIDE_MS = 6000;
    private static final long SAMPLE_MS = 2000;
    private static final long WATCH_MS = 2000;
    private static final long AWAY_END_MS = 8000;       // game ditinggalkan selama ini = sesi otomatis selesai
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
    private FpsRing ringFps;
    private TextView tvGame;
    private String shownPkg = "";
    private View handleBar, handleCapsule;
    private android.animation.ObjectAnimator handlePulse, recPulse;
    private boolean battReceiverOn = false;
    private boolean netFocus = false;
    private WifiManager.WifiLock wifiLock;
    private ImageView icNetFocus;
    private TextView tvNetFocus;
    private TextView tvTemp, tvHint, tvToast, tvClean, tvFps;
    private volatile int lastFps = -1;
    private final Metric[] metrics = new Metric[4];     // CPU, GPU, RAM, Internet
    private static final int M_CPU = 0, M_GPU = 1, M_RAM = 2, M_NET = 3;

    private boolean expanded = false;
    private boolean onRight = true;
    private boolean cleaning = false;
    private boolean recording = false, shooting = false, recBusy = false;
    private long recStart;
    private TextView tvRec;
    private ImageView icRec;
    private View btnRec;
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long sec = (System.currentTimeMillis() - recStart) / 1000;
            if (tvRec != null) tvRec.setText(String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60));
            handler.postDelayed(this, 1000);
        }
    };
    private int slop;
    private String gamePkg = "";
    private String netType = "";

    private HandlerThread sampleThread;
    private Handler sampleHandler;
    private SysStats stats;
    private volatile boolean sampling = false;

    // Catatan sesi (untuk ringkasan)
    private long sessionStart = 0;
    private int startBatt = 0;
    private float peakTemp = 0f;
    private int cpuSum, cpuN, gpuSum, gpuN;
    private int sReq, sFrozen;
    private long sFreed;
    private boolean sBlocked;

    // Aktif otomatis
    private boolean autoStarted = false;
    private volatile boolean starting = false;
    private String suppressPkg = null;
    private String curFg = null;
    private long lastEventTs = 0;
    private int awayMs = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            trackStats();
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
            if (expanded && !RecordService.active) lastFps = FpsMeter.read(gamePkg);   // berat: hanya saat panel terbuka
            handler.post(new Runnable() {
                @Override public void run() { applyStats(snap); }
            });
            Handler h = sampleHandler;
            if (sampling && h != null) h.postDelayed(this, SAMPLE_MS);
        }
    };
    private final Runnable breakTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int min = Prefs.get(GameService.this).getInt("break_min", 60);
            String msg = "Sudah " + min + " menit main. Istirahat sebentar dan minum air.";
            vibrate(250);
            if (root != null) {
                openPanel(10000);
                flashToast(msg, 9000);
            } else {
                postSimpleNotification(NOTIF_BREAK_ID, "Waktunya istirahat", msg);
            }
            scheduleBreak();
        }
    };
    private final Runnable watchTask = new Runnable() {
        @Override public void run() {
            try {
                watchTick();
            } catch (Exception ignored) {
            }
            if (watching) handler.postDelayed(this, WATCH_MS);
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

    /** Nyalakan atau matikan layanan siaga sesuai setelan dan izin. Aman dipanggil berulang. */
    static void syncWatch(Context c) {
        boolean want = Prefs.flag(c, "auto") && PermHelper.hasUsageAccess(c);
        try {
            if (want && !watching) {
                Intent i = new Intent(c, GameService.class).setAction(ACTION_WATCH);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else if (!want && watching) {
                c.startService(new Intent(c, GameService.class).setAction(ACTION_UNWATCH));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean watchWanted() {
        return Prefs.flag(this, "auto") && PermHelper.hasUsageAccess(this);
    }

    private void goForeground(Notification n) {
        // Android 14+ mewajibkan tipe layanan foreground.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            if (running) {
                suppressPkg = gamePkg;
                endGame(false);
            } else if (!watching) {
                stopSelf();
            }
            return watching ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_UNWATCH.equals(action)) {
            stopWatching();
            if (!running) {
                goForeground(buildWatchNotification());
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_WATCH.equals(action) || intent == null || intent.getStringExtra(MainActivity.EXTRA_GAME) == null) {
            if (!running) goForeground(buildWatchNotification());
            if (!watchWanted()) {
                stopWatching();
                if (!running) {
                    stopForeground(true);
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            startWatching();
            return START_STICKY;
        }

        beginGame(SessionRunner.Result.fromIntent(intent), false);
        return watching ? START_STICKY : START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- mulai / selesai mode game

    private void beginGame(SessionRunner.Result p, boolean auto) {
        if (running) return;
        gamePkg = p.pkg == null ? "" : p.pkg;
        killLoop = p.kill;
        blockNotifs = p.notif;
        freezeMode = p.freezeOn;
        netFocus = p.netOk;
        autoStarted = auto;
        awayMs = 0;

        goForeground(buildNotification());
        running = true;

        sessionStart = SystemClock.elapsedRealtime();
        startBatt = SysInfo.battery(this).level;
        peakTemp = 0f;
        cpuSum = cpuN = gpuSum = gpuN = 0;
        sReq = p.requested;
        sFrozen = p.frozen;
        sFreed = p.freedMb;
        sBlocked = p.notif;
        GameNotifService.resetMissed();

        if (Prefs.flag(this, "netfocus") && wifiLock == null) acquireWifiLock();
        if (blockNotifs) GameNotifService.clearAll();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, KILL_INTERVAL_MS);
        scheduleBreak();

        if (p.overlay && canDraw() && root == null) {
            try {
                showPanel();
                if (root != null) openPanel(FIRST_HIDE_MS);   // tampil sebentar lalu masuk ke tepi
            } catch (Throwable e) {
                // Panel tidak boleh membuat layanan (dan game) ikut terganggu.
                android.util.Log.e("GameBoost", "panel gagal", e);
                try {
                    if (root != null && wm != null) wm.removeView(root);
                } catch (Throwable ignored) {
                }
                root = null;
                expanded = false;
                android.widget.Toast.makeText(this, "Panel gagal dibuat: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " - " + e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
            }
        }
        // Data grafik terus dicatat selama mode game, walau panel sedang tersimpan di tepi layar.
        if (root != null && !sampling) startSampling();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (root != null && p.requested > 0) {
            toastIcon(String.format(Locale.US, "%d aplikasi dihentikan  •  RAM +%d MB",
                    p.requested, p.freedMb), R.drawable.ic_check, R.color.ok);
        }
    }

    /** Akhiri mode game: pulihkan semuanya, simpan ringkasan, lalu kembali siaga atau berhenti. */
    private void endGame(boolean fromDestroy) {
        if (!running) return;
        running = false;
        blockNotifs = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideToast);
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(breakTask);
        stopSampling();
        RecordService.disarm(this);
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) wifiLock.release();
            } catch (Exception ignored) {
            }
            wifiLock = null;
        }
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
            sampleHandler = null;
        }
        stopHandleAnim();
        if (recPulse != null) {
            recPulse.cancel();
            recPulse = null;
        }
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        expanded = false;

        GameBooster.restoreDnd(this);
        // Pulihkan aplikasi yang dibekukan dan setelan performa (butuh Shizuku, jadi di thread lain).
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { GameBooster.restoreSession(appCtx); }
        }).start();

        long ms = SystemClock.elapsedRealtime() - sessionStart;
        Map<String, Integer> missed = GameNotifService.takeMissed();
        try {
            saveSummary(ms, missed);
        } catch (Exception ignored) {
        }
        autoStarted = false;

        if (fromDestroy) return;
        if (watchWanted()) {
            goForeground(buildWatchNotification());
        } else {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stopWatching();
        if (running) endGame(true);
        handler.removeCallbacksAndMessages(null);
        stopSampling();
        if (sampleThread != null) {
            sampleThread.quitSafely();
            sampleThread = null;
        }
        RecordService.disarm(this);
        if (RecordService.listener == recListener) RecordService.listener = null;
        stopForeground(true);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- aktif otomatis

    private void startWatching() {
        if (watching) return;
        watching = true;
        lastEventTs = System.currentTimeMillis() - 60000;
        handler.removeCallbacks(watchTask);
        handler.post(watchTask);
    }

    private void stopWatching() {
        watching = false;
        handler.removeCallbacks(watchTask);
    }

    /** Aplikasi yang sedang di layar, dibaca dari peristiwa penggunaan (butuh Akses penggunaan). */
    private String pollForeground() {
        UsageStatsManager u = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (u == null) return curFg;
        long now = System.currentTimeMillis();
        UsageEvents ev = u.queryEvents(Math.max(lastEventTs, now - 60000), now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                curFg = e.getPackageName();
            } else if (t == UsageEvents.Event.MOVE_TO_BACKGROUND && e.getPackageName().equals(curFg)) {
                curFg = null;
            }
        }
        lastEventTs = now;
        return curFg;
    }

    private boolean isTransientFg(String pkg) {
        return pkg.equals(getPackageName()) || pkg.equals("com.android.systemui");
    }

    private void watchTick() {
        if (!watchWanted()) {
            stopWatching();
            if (!running) {
                stopForeground(true);
                stopSelf();
            }
            return;
        }
        String fg = pollForeground();
        if (suppressPkg != null && fg != null && !fg.equals(suppressPkg)) suppressPkg = null;

        if (!running) {
            if (!starting && fg != null && !fg.equals(suppressPkg) && GameDetect.isMine(this, fg)) {
                startAuto(fg);
            }
        } else if (autoStarted) {
            if (fg == null || fg.equals(gamePkg) || isTransientFg(fg)) {
                awayMs = 0;
            } else {
                awayMs += WATCH_MS;
                if (awayMs >= AWAY_END_MS) endGame(false);
            }
        }
    }

    private void startAuto(final String pkg) {
        starting = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                SessionRunner.Result r = null;
                try {
                    r = SessionRunner.run(app, pkg, null, false);
                } catch (Throwable ignored) {
                }
                final SessionRunner.Result done = r;
                handler.post(new Runnable() {
                    @Override public void run() {
                        starting = false;
                        if (done == null) suppressPkg = pkg;      // gagal: jangan dicoba terus tiap 2 detik
                        else if (!running && watching) beginGame(done, true);
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- ringkasan sesi

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void saveSummary(long ms, Map<String, Integer> missed) {
        if (ms < 15000) return;                       // sesi sangat singkat tidak perlu dirangkum
        List<String> lines = new ArrayList<>();

        int battNow = SysInfo.battery(this).level;
        String l1 = "Main " + SessionStats.duration(ms);
        if (startBatt - battNow > 0) l1 += "  •  baterai −" + (startBatt - battNow) + "%";
        lines.add(l1);

        String l2 = "";
        if (sFreed > 0) l2 += "RAM +" + sFreed + " MB";
        if (sFrozen > 0 || sReq > 0) {
            if (!l2.isEmpty()) l2 += "  •  ";
            l2 += sFrozen > 0 ? sFrozen + " aplikasi dibekukan" : sReq + " aplikasi dihentikan";
        }
        if (!l2.isEmpty()) lines.add(l2);

        String l3 = String.format(Locale.US, "Suhu tertinggi %.1f°C", peakTemp);
        if (cpuN > 0) l3 += "  •  CPU rata-rata " + (cpuSum / cpuN) + "%";
        if (gpuN > 0) l3 += "  •  GPU rata-rata " + (gpuSum / gpuN) + "%";
        if (peakTemp > 0f || cpuN > 0) lines.add(l3);

        if (sBlocked) {
            int total = 0;
            for (int v : missed.values()) total += v;
            if (total == 0) {
                lines.add("Tidak ada notifikasi yang terlewat");
            } else {
                List<Map.Entry<String, Integer>> es = new ArrayList<>(missed.entrySet());
                Collections.sort(es, new Comparator<Map.Entry<String, Integer>>() {
                    @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                        return b.getValue() - a.getValue();
                    }
                });
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < es.size() && i < 3; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(labelOf(es.get(i).getKey())).append(' ').append(es.get(i).getValue());
                }
                if (es.size() > 3) sb.append(", dll.");
                lines.add("Kamu melewatkan " + total + " notifikasi: " + sb);
            }
        }

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) body.append('\n');
            body.append(lines.get(i));
        }
        String title = "Sesi terakhir  •  " + labelOf(gamePkg);
        SessionStats.save(this, title, body.toString(), System.currentTimeMillis());
        postSimpleNotification(NOTIF_SUMMARY_ID, "Sesi selesai  •  " + labelOf(gamePkg), body.toString());
    }

    private void trackStats() {
        float t = SysInfo.battery(this).tempC;
        if (t > peakTemp) peakTemp = t;
    }

    // ---------------------------------------------------------------- pengingat istirahat

    private void scheduleBreak() {
        handler.removeCallbacks(breakTask);
        if (!Prefs.flag(this, "breakremind")) return;
        long ms = Prefs.get(this).getInt("break_min", 60) * 60000L;
        handler.postDelayed(breakTask, ms);
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
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

    /** Notifikasi saat siaga: menunggu game dibuka. */
    @SuppressWarnings("deprecation")
    private Notification buildWatchNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_WATCH, "Siaga otomatis",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_WATCH)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("GameBoost siaga")
                .setContentText("Mode game nyala otomatis saat game dibuka")
                .setContentIntent(open)
                .setOngoing(true);
        return b.build();
    }

    /** Notifikasi biasa (ringkasan sesi, pengingat istirahat). */
    @SuppressWarnings("deprecation")
    private void postSimpleNotification(int id, String title, String text) {
        postNotification(id, title, text, false);
    }

    /** Notifikasi tanpa suara dan tanpa pop-up (hasil foto/rekam saat sedang main). */
    private void postQuietNotification(int id, String title, String text) {
        postNotification(id, title, text, true);
    }

    @SuppressWarnings("deprecation")
    private void postNotification(int id, String title, String text, boolean quiet) {
        try {
            String channel = quiet ? CHANNEL_CAPTURE : CHANNEL_SUMMARY;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = quiet
                        ? new NotificationChannel(CHANNEL_CAPTURE, "Hasil foto dan rekam",
                                NotificationManager.IMPORTANCE_LOW)
                        : new NotificationChannel(CHANNEL_SUMMARY, "Ringkasan sesi",
                                NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            if (Build.VERSION.SDK_INT >= 33 && !PermHelper.hasNotifPerm(this)) return;
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent open = PendingIntent.getActivity(this, 2,
                    new Intent(this, MainActivity.class), piFlags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, channel)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text.split("\n")[0])
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true);
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
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
        View v = PanelStyle.addCell(row, label, color, fixedMax, autoFloor);
        Metric m = new Metric();
        m.value = v.findViewById(R.id.cellValue);
        m.sub = v.findViewById(R.id.cellSub);
        m.spark = v.findViewById(R.id.cellSpark);
        return m;
    }

    private void showPanel() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ContextWrapper themed = new ContextThemeWrapper(this, R.style.Theme_GameBoost);
        root = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null);

        handle = root.findViewById(R.id.handle);
        card = root.findViewById(R.id.card);
        icCollapse = root.findViewById(R.id.icCollapse);
        ringFps = root.findViewById(R.id.fpsRing);
        tvGame = root.findViewById(R.id.tvGame);
        handleBar = root.findViewById(R.id.handleBar);
        handleCapsule = root.findViewById(R.id.handleCapsule);
        icNetFocus = root.findViewById(R.id.icNetFocus);
        tvNetFocus = root.findViewById(R.id.tvNetFocus);
        tvTemp = root.findViewById(R.id.tvTemp);
        tvHint = root.findViewById(R.id.tvHint);
        tvFps = root.findViewById(R.id.tvFps);
        tvToast = root.findViewById(R.id.tvToast);
        tvClean = root.findViewById(R.id.tvClean);
        btnClean = root.findViewById(R.id.btnClean);
        btnRec = root.findViewById(R.id.btnRec);
        tvRec = root.findViewById(R.id.tvRec);
        icRec = root.findViewById(R.id.icRec);
        slop = ViewConfiguration.get(this).getScaledTouchSlop();
        onRight = !Prefs.flag(this, "panel_left");

        LinearLayout row1 = root.findViewById(R.id.gridRow1);
        LinearLayout row2 = root.findViewById(R.id.gridRow2);
        metrics[M_CPU] = addMetric(row1, "CPU", 0xFF0A84FF, 100f, 1f);
        metrics[M_GPU] = addMetric(row1, "GPU", 0xFFBF5AF2, 100f, 1f);
        metrics[M_RAM] = addMetric(row2, "RAM", 0xFF30D158, 100f, 1f);
        metrics[M_NET] = addMetric(row2, "NET", 0xFFFF9F0A, 0f, 64f);   // skala otomatis (KB/s)

        // Panah tutup menunjuk ke arah tepi tempat panel menempel.
        icCollapse.setRotation(onRight ? 0f : 180f);
        if (!onRight) {
            ((LinearLayout.LayoutParams) handleCapsule.getLayoutParams()).gravity = Gravity.START;
            handleCapsule.setScaleX(-1f);
        }

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
            @Override public void onClick(View v) {
                // Akhiri sesi saja; layanan siaga (aktif otomatis) tetap hidup.
                handler.post(new Runnable() {
                    @Override public void run() {
                        suppressPkg = gamePkg;
                        endGame(false);
                    }
                });
            }
        });
        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cleanNow(); }
        });
        root.findViewById(R.id.btnShot).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { takeShot(); }
        });
        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecord(); }
        });
        PanelStyle.apply(root, PanelStyle.opacity(this));
        Motion.pressAll(card);
        RecordService.listener = recListener;
        recording = RecordService.active;
        recStart = RecordService.startedAt;
        if (recording) handler.post(recTick);
        updateRecUi();

        card.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
        handle.setAlpha(0.8f);
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
        updateNetChip();
        startHandleAnim();

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
        boolean anim = Motion.enabled(this);
        card.animate().translationX(0f).alpha(1f).setDuration(anim ? 340 : 0)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f)).start();
        stopHandleAnim();
        if (anim) {
            ViewGroup cg = (ViewGroup) card;
            int k = 0;
            for (int i = 0; i < cg.getChildCount(); i++) {
                View ch = cg.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE) continue;
                Motion.rise(ch, 60L + 70L * k++);
            }
            if (ringFps != null) ringFps.replay();
        }
        root.post(new Runnable() {
            @Override public void run() {
                fitScale();
                clampY();
                applyLayout();
            }
        });
        refresh();
        scheduleAutoHide(hideAfterMs);
    }

    /** Panel meluncur kembali ke tepi layar dan tinggal gagang tipisnya. */
    private void closePanel() {
        if (root == null || !expanded) return;
        expanded = false;
        handler.removeCallbacks(autoHide);
        float w = dp(CARD_W_DP);
        card.animate().cancel();
        card.animate().translationX(onRight ? w : -w).alpha(0f).setDuration(Motion.enabled(this) ? 220 : 0)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (root == null || expanded) return;
                        card.setVisibility(View.GONE);
                        card.setTranslationX(0f);
                        handle.setVisibility(View.VISIBLE);
                        handle.setAlpha(0f);
                        handle.animate().alpha(0.8f).setDuration(220).start();
                        startHandleAnim();
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
        if (root == null) return;

        Metric cpu = metrics[M_CPU];
        if (s.cpu >= 0) {
            cpu.value.setText(s.cpu + "%");
            cpuSum += s.cpu;
            cpuN++;
            cpu.spark.push(s.cpu);
            cpu.sub.setText(s.cpuByFreq ? "perkiraan dari frekuensi" : "beban prosesor");
        } else {
            cpu.value.setText("—");
            cpu.sub.setText("tidak terbaca");
        }

        Metric gpu = metrics[M_GPU];
        if (s.gpu >= 0) {
            gpu.value.setText(s.gpu + "%");
            gpuSum += s.gpu;
            gpuN++;
            gpu.spark.push(s.gpu);
            String name = s.gpuModel != null ? s.gpuModel : "beban grafis";
            gpu.sub.setText(s.gpuByFreq ? name + " • dari frekuensi" : name);
        } else {
            gpu.value.setText("—");
            gpu.sub.setText(ShizukuHelper.ready() ? "tidak tersedia di HP ini" : "butuh Shizuku");
        }

        Metric net = metrics[M_NET];
        if (s.rxBps >= 0) {
            net.value.setText(SysStats.speed(s.rxBps));
            Icons.start(net.value, R.drawable.ic_arrow_down, 11, 0xFFFF9F0A);
            net.spark.push(s.rxBps / 1024f);
            net.sub.setText(SysStats.speed(s.txBps) + (netType.isEmpty() ? "" : "  •  " + netType));
            Icons.start(net.sub, R.drawable.ic_arrow_up, 9, 0xB8FFFFFF);
        } else {
            net.value.setText("—");
            Icons.clear(net.value);
            net.sub.setText(netType);
            Icons.clear(net.sub);
        }

        int fps = lastFps;
        if (ringFps != null) ringFps.setFps(fps);
        if (tvFps != null) {
            if (fps < 0) {
                tvFps.setText(fpsReason(fps));
                tvFps.setVisibility(View.VISIBLE);
            } else {
                tvFps.setVisibility(View.GONE);
            }
        }

        // RAM ikut dicatat di siklus yang sama supaya keempat grafik sinkron.
        metrics[M_RAM].spark.push(SysInfo.ram(this).usedPct);
    }

    private static String fpsReason(int code) {
        switch (code) {
            case FpsMeter.ERR_SHIZUKU: return "FPS: butuh Shizuku";
            case FpsMeter.ERR_NOGAME: return "FPS: game belum dikenali";
            case FpsMeter.ERR_NOLAYER: return "FPS: layar game tak ketemu";
            default: return "FPS: tak terbaca";
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
                        toastIcon(String.format(Locale.US, "RAM +%d MB  •  %d aplikasi dihentikan",
                                r.freedMb, r.requested), R.drawable.ic_check, R.color.ok);
                        if (expanded) scheduleAutoHide(AUTO_HIDE_MS);
                    }
                });
            }
        }).start();
    }

    private void updateRecUi() {
        if (btnRec == null) return;
        btnRec.setTag(recording);
        btnRec.setBackground(PanelStyle.recButton(this, recording, PanelStyle.opacity(this)));
        icRec.setImageResource(recording ? R.drawable.ic_stop_sq : R.drawable.ic_record);
        if (!recording) tvRec.setText("Rekam");
        if (recPulse != null) {
            recPulse.cancel();
            recPulse = null;
        }
        btnRec.setAlpha(1f);
        if (recording && Motion.enabled(this)) {
            recPulse = android.animation.ObjectAnimator.ofFloat(btnRec, View.ALPHA, 1f, 0.7f);
            recPulse.setDuration(900);
            recPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            recPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            recPulse.start();
        }
    }

    /** Screenshot: panel disembunyikan sebentar supaya tidak ikut terfoto. */
    private void takeShot() {
        if (shooting) return;
        if (!ShizukuHelper.ready()) {
            flashToast("Butuh Shizuku aktif");
            return;
        }
        shooting = true;
        handler.removeCallbacks(autoHide);
        closePanel();
        final View r = root;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (r != null) r.setVisibility(View.INVISIBLE);
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                        }
                        final String path = ScreenCapture.screenshot();
                        handler.post(new Runnable() {
                            @Override public void run() {
                                shooting = false;
                                if (root != null) root.setVisibility(View.VISIBLE);
                                if (path != null) {
                                    vibrate(25);
                                    postQuietNotification(NOTIF_SHOT_ID, "Screenshot tersimpan",
                                            "Folder Pictures/GameBoost");
                                    android.widget.Toast.makeText(GameService.this,
                                            "Screenshot tersimpan", android.widget.Toast.LENGTH_SHORT).show();
                                } else {
                                    openPanel(AUTO_HIDE_MS);
                                    flashToast("Screenshot gagal");
                                }
                            }
                        });
                    }
                }).start();
            }
        }, 330);
    }

    private boolean recStarting = false;

    private void scaleForRecording(final boolean on) {
        final Context app = getApplicationContext();
        final String game = gamePkg;
        new Thread(new Runnable() {
            @Override public void run() { GameBooster.setRecordingScale(app, game, on); }
        }).start();
    }

    private final RecordService.Listener recListener = new RecordService.Listener() {
        @Override public void onStarted(boolean withAudio) {
            recStarting = false;
            recording = true;
            recStart = RecordService.startedAt;
            handler.removeCallbacks(recTick);
            handler.post(recTick);
            vibrate(25);
            scaleForRecording(true);
            updateRecUi();
            android.content.SharedPreferences rp = Prefs.get(GameService.this);
            if (!withAudio && (rp.getBoolean(RecordService.PREF_GAME, true) || rp.getBoolean(RecordService.PREF_MIC, false)))
                flashToast("Merekam tanpa suara (izin suara ditolak atau tidak bisa)");
        }

        @Override public void onStopped(boolean saved, String message) {
            if (RecordService.MSG_NEED_PERM.equals(message)) {       // izin hilang: minta ulang
                askRecordPermission();
                return;
            }
            boolean was = recording;
            recStarting = false;
            recording = false;
            handler.removeCallbacks(recTick);
            if (was) scaleForRecording(false);
            updateRecUi();
            if (saved) {
                postQuietNotification(NOTIF_SHOT_ID + 1, "Rekaman tersimpan", "Folder Movies/GameBoost");
                if (root != null) toastIcon("Rekaman tersimpan", R.drawable.ic_check, R.color.ok);
                else android.widget.Toast.makeText(GameService.this, "Rekaman tersimpan",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else if (message != null) {
                if (root != null) flashToast(message);
                else android.widget.Toast.makeText(GameService.this, message,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** Rekam layar + suara game. Izin biasanya sudah disimpan di awal sesi, jadi tidak pindah layar. */
    private void toggleRecord() {
        RecordService.listener = recListener;
        if (RecordService.active) {
            RecordService.stop(this);
            return;
        }
        if (recStarting) return;
        recStarting = true;
        handler.removeCallbacks(autoHide);
        closePanel();
        if (RecordService.armed) {
            RecordService.start(this);
        } else {
            askRecordPermission();
        }
    }

    /** Cadangan bila izin belum disimpan (mis. mode game aktif otomatis): layar bening sesaat. */
    private void askRecordPermission() {
        try {
            startActivity(new Intent(this, RecordPermActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            recStarting = false;
            flashToast("Tidak bisa membuka izin rekam");
        }
    }

    private void flashToast(String msg) {
        flashToast(msg, 4500);
    }

    /** Pesan singkat dengan ikon di depannya (pengganti simbol/emoji). */
    private void toastIcon(String msg, int iconRes, int colorRes) {
        flashToast(msg, 4500);
        if (tvToast == null) return;
        int c = SysInfo.color(this, colorRes);
        tvToast.setTextColor(c);
        Icons.start(tvToast, iconRes, 14, c);
    }

    private void flashToast(String msg, long ms) {
        if (tvToast == null) return;
        Icons.clear(tvToast);
        tvToast.setTextColor(SysInfo.color(this, R.color.ok));
        tvToast.setText(msg);
        tvToast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, ms);
    }

    /** Minta Wi-Fi tetap aktif dan, di Android 10+, memakai mode latensi rendah selama bermain. */
    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (w == null) return;
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            WifiManager.WifiLock l = w.createWifiLock(mode, "GameBoost");
            l.setReferenceCounted(false);
            l.acquire();
            wifiLock = l;
        } catch (Exception e) {
            wifiLock = null;
        }
    }

    /** Ikon dan teks status internet di panel: hijau jika fokus ke game aktif. */
    private void updateNetChip() {
        if (tvNetFocus == null) return;
        boolean wifi = wifiLock != null;
        String text;
        if (netFocus && wifi) text = "Fokus ke game  •  Wi-Fi latensi rendah";
        else if (netFocus) text = "Internet latar belakang diputus";
        else if (wifi) text = "Wi-Fi latensi rendah (tanpa Shizuku)";
        else text = "Internet latar belakang belum dibatasi";
        int color = SysInfo.color(this, netFocus || wifi ? R.color.ok : R.color.muted);
        tvNetFocus.setText(text);
        tvNetFocus.setTextColor(color);
        icNetFocus.setColorFilter(color);
    }

    /** Suhu (dari baterai) diperbarui saat level/suhu berubah; ikon baterai sudah tidak dipakai. */
    private void updateBattery() {
        if (root == null) return;
        updateTemp();
    }

    private void updateTemp() {
        SysInfo.Batt b = SysInfo.battery(this);
        tvTemp.setText(String.format(Locale.US, "%.1f°C", b.tempC));
        tvTemp.setTextColor(SysInfo.color(this, b.tempC >= 43f ? R.color.danger
                : (b.tempC >= 40f ? R.color.warn : R.color.text)));
        tvHint.setText(b.tempC >= 43f ? "Panas" : (b.tempC >= 40f ? "Hangat" : "Stabil"));
        tvHint.setTextColor(SysInfo.color(this, b.tempC >= 43f ? R.color.danger
                : (b.tempC >= 40f ? R.color.warn : R.color.ok)));
    }

    /** Gagang bernapas pelan dan sesekali bergeser sedikit ke dalam supaya mudah ditemukan. */
    private final Runnable handleNudge = new Runnable() {
        @Override public void run() {
            if (root == null || expanded || handleCapsule == null) return;
            float dx = dp(7) * (onRight ? -1f : 1f);
            handleCapsule.animate().translationX(dx).setDuration(200).setInterpolator(Motion.IOS)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (handleCapsule != null) handleCapsule.animate().translationX(0f).setDuration(420)
                                    .setInterpolator(Motion.SPRING).start();
                        }
                    }).start();
            handler.postDelayed(this, 4500);
        }
    };

    /** Layar pendek (lanskap): kartu diperkecil seragam supaya utuh terlihat, tanpa mengubah desain. */
    private void fitScale() {
        if (root == null || !(root instanceof ScaleFrame) || card == null) return;
        int ch = card.getHeight();
        if (ch <= 0) return;
        float avail = screen()[1] - dp(16);
        ((ScaleFrame) root).setCardScale(Math.min(1f, avail / ch));
    }

    private void startHandleAnim() {
        stopHandleAnim();
        if (handleBar == null || !Motion.enabled(this)) return;
        handlePulse = android.animation.ObjectAnimator.ofFloat(handleBar, View.ALPHA, 0.35f, 0.8f);
        handlePulse.setDuration(1200);
        handlePulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        handlePulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        handlePulse.start();
        handler.postDelayed(handleNudge, 4500);
    }

    private void stopHandleAnim() {
        handler.removeCallbacks(handleNudge);
        if (handlePulse != null) {
            handlePulse.cancel();
            handlePulse = null;
        }
        if (handleBar != null) handleBar.setAlpha(1f);
        if (handleCapsule != null) {
            handleCapsule.animate().cancel();
            handleCapsule.setTranslationX(0f);
        }
    }

    /** Bagian yang ringan dan dibaca di thread utama: baterai, suhu, RAM, jenis jaringan. */
    private void refresh() {
        if (root == null) return;
        SysInfo.Ram r = SysInfo.ram(this);
        SysInfo.Batt b = SysInfo.battery(this);
        netType = SysStats.networkType(this);

        if (tvGame != null && gamePkg != null && !gamePkg.isEmpty() && !gamePkg.equals(shownPkg)) {
            shownPkg = gamePkg;
            try {
                tvGame.setText(getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(gamePkg, 0)));
            } catch (Exception ignore) {
                tvGame.setText("Mode game");
            }
        }

        Metric ram = metrics[M_RAM];
        ram.value.setText(r.usedPct + "%");
        ram.sub.setText(String.format(Locale.US, "%d / %d MB", r.usedMb, r.totalMb));

        updateTemp();

        // Jaga posisi tetap di dalam layar jika layar diputar.
        fitScale();
        int oy = lp.y;
        clampY();
        if (oy != lp.y) applyLayout();
    }
}
