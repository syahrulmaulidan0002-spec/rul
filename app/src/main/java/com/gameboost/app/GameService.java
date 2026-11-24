package com.gameboost.app;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
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
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import java.util.Locale;

/**
 * Layanan mode game. Menampilkan jendela mengambang bergaya iOS:
 * kartu lengkap (cincin RAM, suhu, baterai, tombol) yang bisa dikecilkan menjadi pil mungil,
 * digeser bebas, dan otomatis menempel ke tepi layar. Saat berhenti, Jangan Ganggu dikembalikan.
 */
public class GameService extends Service {

    public static volatile boolean running = false;
    /** true jika semua notifikasi yang masuk harus dihapus (dibaca oleh GameNotifService). */
    public static volatile boolean blockNotifs = false;

    static final String ACTION_STOP = "com.gameboost.app.action.STOP";
    private static final String CHANNEL = "gameboost_mode";
    private static final int NOTIF_ID = 7;
    private static final long AUTO_COLLAPSE_MS = 7000;
    private static final long KILL_INTERVAL_MS = 20000;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View root, pill, card, btnClean;
    private RingView ring;
    private TextView tvPill, tvRam, tvTemp, tvBatt, tvHint, tvToast, tvClean;

    private boolean expanded = true;
    private boolean onRight = false;
    private boolean cleaning = false;
    private int slop;
    private String gamePkg = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };
    private final Runnable hideToast = new Runnable() {
        @Override public void run() {
            if (tvToast != null) tvToast.setVisibility(View.GONE);
        }
    };
    private final Runnable autoCollapse = new Runnable() {
        @Override public void run() {
            collapse();
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

        if (overlay && canDraw() && root == null) showPanel();
        if (root != null && requested > 0) {
            flashToast(String.format(Locale.US, "✓ %d aplikasi dihentikan  •  RAM +%d MB",
                    requested, freed));
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideToast);
        handler.removeCallbacks(autoCollapse);
        handler.removeCallbacks(watchdog);
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

    private void showPanel() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ContextWrapper themed = new ContextThemeWrapper(this, R.style.Theme_GameBoost);
        root = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null);

        pill = root.findViewById(R.id.pill);
        card = root.findViewById(R.id.card);
        ring = root.findViewById(R.id.ring);
        tvPill = root.findViewById(R.id.tvPill);
        tvRam = root.findViewById(R.id.tvRam);
        tvTemp = root.findViewById(R.id.tvTemp);
        tvBatt = root.findViewById(R.id.tvBatt);
        tvHint = root.findViewById(R.id.tvHint);
        tvToast = root.findViewById(R.id.tvToast);
        tvClean = root.findViewById(R.id.tvClean);
        btnClean = root.findViewById(R.id.btnClean);
        slop = ViewConfiguration.get(this).getScaledTouchSlop();

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,   // layar tidak redup saat main
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(10);
        lp.y = dp(72);

        // Seret di bagian mana pun yang bukan tombol; ketuk pil untuk membuka kartu.
        root.setOnTouchListener(new DragTouch());

        root.findViewById(R.id.btnCollapse).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collapse(); }
        });
        root.findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        });
        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cleanNow(); }
        });

        pill.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            return;
        }
        scheduleAutoCollapse();
    }

    /** Menangani seretan bebas dan ketukan pada pil. */
    private final class DragTouch implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(autoCollapse);
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                    if (moved) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        clampToScreen();
                        applyLayout();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP && !expanded) {
                        expand();
                    } else if (!expanded) {
                        snapToEdge();
                    } else {
                        scheduleAutoCollapse();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void clampToScreen() {
        if (root == null) return;
        int[] s = screen();
        int w = root.getWidth();
        int h = root.getHeight();
        lp.x = Math.max(0, Math.min(lp.x, s[0] - w));
        lp.y = Math.max(0, Math.min(lp.y, s[1] - h));
    }

    private void animateX(int to) {
        ValueAnimator a = ValueAnimator.ofInt(lp.x, to);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator an) {
                lp.x = (Integer) an.getAnimatedValue();
                applyLayout();
            }
        });
        a.start();
    }

    private void expand() {
        if (root == null || expanded) return;
        expanded = true;
        int[] s = screen();
        int cw = dp(252);

        pill.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setScaleX(0.85f);
        card.setScaleY(0.85f);
        card.setPivotX(onRight ? cw : 0);
        card.setPivotY(0);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();

        animateX(Math.max(dp(8), Math.min(lp.x, s[0] - cw - dp(8))));
        scheduleAutoCollapse();
    }

    private void collapse() {
        if (root == null || !expanded) return;
        expanded = false;
        handler.removeCallbacks(autoCollapse);
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (root == null) return;
                        card.setVisibility(View.GONE);
                        pill.setVisibility(View.VISIBLE);
                        pill.setAlpha(0f);
                        pill.setScaleX(0.8f);
                        pill.setScaleY(0.8f);
                        pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                        snapToEdge();
                    }
                }).start();
    }

    /** Pil menempel ke tepi kiri atau kanan, seperti tombol AssistiveTouch di iOS. */
    private void snapToEdge() {
        if (root == null) return;
        int[] s = screen();
        pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = pill.getMeasuredWidth();
        onRight = lp.x + w / 2 > s[0] / 2;
        animateX(onRight ? s[0] - w - dp(8) : dp(8));
    }

    private void scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapse);
        handler.postDelayed(autoCollapse, AUTO_COLLAPSE_MS);
    }

    private void cleanNow() {
        if (cleaning) return;
        cleaning = true;
        tvClean.setText("Membersihkan…");
        handler.removeCallbacks(autoCollapse);
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
                        scheduleAutoCollapse();
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

    private void refresh() {
        if (root == null) return;
        SysInfo.Ram r = SysInfo.ram(this);
        SysInfo.Batt b = SysInfo.battery(this);

        int ramColor = SysInfo.color(this, r.usedPct >= 88 ? R.color.danger
                : (r.usedPct >= 75 ? R.color.warn : R.color.ok));
        ring.setValue(r.usedPct, ramColor);

        tvPill.setText(String.format(Locale.US, "%d%%  %.0f°", r.usedPct, b.tempC));
        tvRam.setText(String.format(Locale.US, "%d / %d MB", r.usedMb, r.totalMb));
        tvTemp.setText(String.format(Locale.US, "%.1f°C", b.tempC));
        tvTemp.setTextColor(SysInfo.color(this, b.tempC >= 43f ? R.color.danger
                : (b.tempC >= 40f ? R.color.warn : R.color.text)));
        tvBatt.setText(String.format(Locale.US, "%d%%%s", b.level, b.charging ? " ⚡" : ""));
        tvHint.setText(b.tempC >= 43f ? "Perangkat panas, turunkan grafis sebentar"
                : (b.tempC >= 40f ? "Mulai hangat, pantau terus" : "Performa stabil"));

        // Jaga posisi tetap di dalam layar jika layar diputar.
        int ox = lp.x;
        int oy = lp.y;
        clampToScreen();
        if (ox != lp.x || oy != lp.y) applyLayout();
    }
}
