package com.gameboost.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rekam layar dengan suara game: MediaProjection (video) + AudioPlaybackCapture (suara yang
 * keluar dari game), di-encode H.264 + AAC lalu digabung ke satu file MP4.
 */
public class RecordService extends Service {

    static final String ACTION_ARM = "gb.rec.arm";          // simpan izin rekam layar (siaga)
    static final String ACTION_START = "gb.rec.start";      // mulai merekam (butuh siaga)
    static final String ACTION_STOP = "gb.rec.stop";        // berhenti merekam (atau matikan siaga)
    static final String ACTION_DISARM = "gb.rec.disarm";    // matikan siaga sepenuhnya
    static final String MSG_NEED_PERM = "NEED_PERM";
    static final String EX_AUTOSTART = "autostart";
    static final String EX_CODE = "code";
    static final String EX_DATA = "data";
    static final String EX_GAME = "game";
    static final String EX_MIC = "mic";
    static final String PREF_PRESET = "rec_preset";          // 0 Lancar, 1 Seimbang, 2 Tajam
    static final String PREF_FULLSCALE = "rec_fullscale";    // resolusi game 100% saat merekam
    static final String PREF_LOWHZ = "rec_lowhz";            // kunci layar 60 Hz saat merekam
    static final String[] PRESET_LABEL = {"Lancar", "Seimbang", "Tajam"};
    static final String PREF_GAME = "rec_game";
    static final String PREF_MIC = "rec_mic";
    static final String PREF_RES = "rec_res";
    static final int[] RES = {360, 480, 720, 1080, 1440};
    static final String[] RES_LABEL = {"360p", "480p", "720p HD", "1080p Full HD", "1440p (2K)"};
    static final int DEFAULT_RES = 720;

    private static final String CHANNEL = "gameboost_rec";
    private static final int NOTIF_ID = 31;
    private static final int RATE = 44100;
    private static final int FPS = 30;

    interface Listener {
        void onStarted(boolean withAudio);
        void onStopped(boolean saved, String message);
    }

    static volatile boolean active = false;     // sedang merekam
    static volatile boolean armed = false;      // izin rekam layar sudah dipegang (siap merekam)
    static volatile long startedAt = 0;
    static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void stop(Context c) {
        if (active) c.startService(new Intent(c, RecordService.class).setAction(ACTION_STOP));
    }

    /** Simpan izin rekam layar di awal sesi, supaya merekam nanti tidak perlu pindah layar. */
    static void arm(Context c, int code, Intent data, boolean autostart) {
        Intent i = new Intent(c, RecordService.class).setAction(ACTION_ARM)
                .putExtra(EX_CODE, code).putExtra(EX_DATA, data).putExtra(EX_AUTOSTART, autostart);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void start(Context c) {
        c.startService(new Intent(c, RecordService.class).setAction(ACTION_START));
    }

    /** Akhir sesi game: hentikan rekaman bila ada dan lepaskan izin. */
    static void disarm(Context c) {
        if (armed || active) c.startService(new Intent(c, RecordService.class).setAction(ACTION_DISARM));
    }

    private static void started(final boolean audio) {
        final Listener l = listener;
        if (l != null) MAIN.post(new Runnable() { @Override public void run() { l.onStarted(audio); } });
    }

    private static void stopped(final boolean saved, final String msg) {
        final Listener l = listener;
        if (l != null) MAIN.post(new Runnable() { @Override public void run() { l.onStopped(saved, msg); } });
    }

    // ------------------------------------------------------------------------------ state

    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final Object lock = new Object();
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec vEnc, aEnc;
    private AudioRecord aRec, mRec;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private File outFile;
    private Thread vThread, aThread;
    private volatile boolean stopFlag = false;
    private int vTrack = -1, aTrack = -1;
    private boolean hasAudio = false, muxStarted = false;
    private long originUs;
    private long lastV = -1, lastA = -1;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private volatile boolean starting = false;
    private volatile boolean fgOn = false;

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!armed) stopSelf();
            return START_NOT_STICKY;
        }
        String act = intent.getAction();
        if (ACTION_STOP.equals(act)) {
            if (active) finishRecording(); else disarm();
        } else if (ACTION_DISARM.equals(act)) {
            disarm();
        } else if (ACTION_ARM.equals(act)) {
            arm(intent);
        } else if (ACTION_START.equals(act)) {
            startRecording();
        }
        return START_NOT_STICKY;
    }

    private boolean audioGranted() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void arm(Intent intent) {
        boolean mic = Prefs.get(this).getBoolean(PREF_MIC, false) && audioGranted();
        showForeground(mic, false);
        if (armed) {
            if (intent.getBooleanExtra(EX_AUTOSTART, false)) startRecording();
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(intent.getIntExtra(EX_CODE, 0), (Intent) intent.getParcelableExtra(EX_DATA));
            if (projection == null) throw new IllegalStateException("izin ditolak");
            projection.registerCallback(projCb, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            projection = null;
            stopped(false, "Izin rekam gagal");
            fgOn = false;
                stopForeground(true);
            stopSelf();
            return;
        }
        armed = true;
        if (intent.getBooleanExtra(EX_AUTOSTART, false)) startRecording();
    }

    private final MediaProjection.Callback projCb = new MediaProjection.Callback() {
        @Override public void onStop() {
            armed = false;
            projection = null;
            if (active) finishRecording();
            else {
                fgOn = false;
                stopForeground(true);
                stopSelf();
            }
        }
    };

    private void startRecording() {
        if (!armed || projection == null) {
            stopped(false, MSG_NEED_PERM);
            return;
        }
        if (active || starting) return;
        starting = true;
        final boolean wantGame = Prefs.get(this).getBoolean(PREF_GAME, true) && audioGranted();
        final boolean wantMic = Prefs.get(this).getBoolean(PREF_MIC, false) && audioGranted();
        showForeground(wantMic, true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    begin(wantGame, wantMic);
                } catch (Throwable t) {
                    abort("Rekam gagal: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                } finally {
                    starting = false;
                }
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void showForeground(boolean mic, boolean recording) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rekam layar",
                    NotificationManager.IMPORTANCE_LOW));
        }
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 7,
                new Intent(this, RecordService.class).setAction(ACTION_STOP), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(recording ? "Merekam layar" : "Siap merekam")
                .setContentText(recording ? "Ketuk untuk berhenti" : "Izin rekam layar aktif selama mode game")
                .setContentIntent(stop).setOngoing(true);
        Notification n = b.build();
        if (fgOn) {                                   // sudah foreground: cukup perbarui teks notifikasi
            nm.notify(NOTIF_ID, n);
            return;
        }
        fgOn = true;
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ------------------------------------------------------------------------------ mulai

    private void begin(boolean wantGame, boolean wantMic) throws Exception {
        resetState();
        // Ukuran: sisi pendek mengikuti pilihan (tidak diperbesar dari layar asli).
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int target = Prefs.get(this).getInt(PREF_RES, DEFAULT_RES);
        float k = Math.min(1f, target / (float) Math.min(dm.widthPixels, dm.heightPixels));
        int w = Math.max(2, Math.round(dm.widthPixels * k) & ~1);
        int h = Math.max(2, Math.round(dm.heightPixels * k) & ~1);
        int bitrate = (int) Math.max(1_500_000L, Math.min(30_000_000L, (long) (w * (long) h * FPS * 0.2)));

        MediaFormat vf = MediaFormat.createVideoFormat("video/avc", w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        vf.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        // Warna sesuai standar layar HP (BT.709) supaya tidak pucat saat diputar.
        if (Build.VERSION.SDK_INT >= 24) {
            vf.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            vf.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            vf.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        vf.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (Build.VERSION.SDK_INT >= 23) vf.setInteger(MediaFormat.KEY_PRIORITY, 0);   // prioritas waktu nyata
        if (Build.VERSION.SDK_INT >= 30) vf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        // Buang frame berlebih sebelum masuk encoder (game 60/90 fps -> rekam 30 fps): beban encoder turun.
        vf.setFloat("max-fps-to-encoder", (float) FPS);
        vEnc = hardwareEncoder();
        // Profil High = kualitas lebih baik di bitrate yang sama. Bila encoder menolak, pakai bawaannya.
        try {
            MediaFormat hi = new MediaFormat(vf);
            hi.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            vEnc.configure(hi, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Throwable t) {
            try { vEnc.release(); } catch (Throwable ignored) { }
            vEnc = hardwareEncoder();
            vf.removeKey("max-fps-to-encoder");
            vEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        Surface in = vEnc.createInputSurface();
        vEnc.start();
        display = projection.createVirtualDisplay("GameBoost", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, in, null, null);

        // Suara: suara game (Android 10+) dan/atau mikrofon. Gagal = tetap merekam tanpa itu.
        AudioFormat af = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        int buf = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) * 2, 16384);
        if (wantGame && Build.VERSION.SDK_INT >= 29) {
            try {
                AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                aRec = new AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(buf)
                        .setAudioPlaybackCaptureConfig(cfg).build();
                if (aRec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("audio");
            } catch (Throwable t) {
                try { if (aRec != null) aRec.release(); } catch (Throwable ignored) { }
                aRec = null;
            }
        }
        if (wantMic) {
            try {
                mRec = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (mRec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic");
            } catch (Throwable t) {
                try { if (mRec != null) mRec.release(); } catch (Throwable ignored) { }
                mRec = null;
            }
        }
        if (aRec != null || mRec != null) {
            try {
                MediaFormat afmt = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2);
                afmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                afmt.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
                afmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                aEnc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                aEnc.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aEnc.start();
                hasAudio = true;
            } catch (Throwable t) {
                hasAudio = false;
                try { if (aEnc != null) aEnc.release(); } catch (Throwable ignored) { }
                try { if (aRec != null) aRec.release(); } catch (Throwable ignored) { }
                try { if (mRec != null) mRec.release(); } catch (Throwable ignored) { }
                aRec = null;
                mRec = null;
                aEnc = null;
            }
        }

        openOutput();
        originUs = System.nanoTime() / 1000L;
        active = true;
        startedAt = System.currentTimeMillis();

        vThread = new Thread(new Runnable() { @Override public void run() { videoLoop(); } }, "gb-rec-video");
        vThread.start();
        if (hasAudio) {
            aThread = new Thread(new Runnable() { @Override public void run() { audioLoop(); } }, "gb-rec-audio");
            aThread.start();
        }
        started(hasAudio);
    }

    private void resetState() {
        finishing.set(false);
        stopFlag = false;
        vTrack = -1;
        aTrack = -1;
        hasAudio = false;
        muxStarted = false;
        lastV = -1;
        lastA = -1;
        vThread = null;
        aThread = null;
        outUri = null;
        outFile = null;
    }

    /** Pilih encoder H.264 perangkat keras (hemat CPU). Encoder perangkat lunak dilewati. */
    private static MediaCodec hardwareEncoder() throws Exception {
        try {
            for (MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                String name = ci.getName();
                if (name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.contains(".sw.")) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase("video/avc")) return MediaCodec.createByCodecName(name);
                }
            }
        } catch (Throwable ignored) {
        }
        return MediaCodec.createEncoderByType("video/avc");
    }

    private void openOutput() throws Exception {
        String name = "GB_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GameBoost");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (outUri == null) throw new IllegalStateException("tidak bisa membuat file");
            pfd = getContentResolver().openFileDescriptor(outUri, "rw");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir != null) dir.mkdirs();
            outFile = new File(dir, name);
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
    }

    // ------------------------------------------------------------------------------ muxer

    private void addTrack(boolean video, MediaFormat f) {
        synchronized (lock) {
            int t = muxer.addTrack(f);
            if (video) vTrack = t; else aTrack = t;
            if (vTrack >= 0 && (!hasAudio || aTrack >= 0) && !muxStarted) {
                muxer.start();
                muxStarted = true;
                lock.notifyAll();
            }
        }
    }

    private void write(boolean video, ByteBuffer b, MediaCodec.BufferInfo info) {
        synchronized (lock) {
            long until = System.currentTimeMillis() + 4000;
            while (!muxStarted && !stopFlag && System.currentTimeMillis() < until) {
                try { lock.wait(50); } catch (InterruptedException e) { return; }
            }
            if (!muxStarted) return;
            long pts = Math.max(0L, info.presentationTimeUs);
            if (video) { pts = Math.max(pts, lastV + 1); lastV = pts; }
            else { pts = Math.max(pts, lastA + 1); lastA = pts; }
            info.presentationTimeUs = pts;
            try {
                muxer.writeSampleData(video ? vTrack : aTrack, b, info);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------------------ loop

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int i = vEnc.dequeueOutputBuffer(info, 10_000);
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, vEnc.getOutputFormat());
                } else if (i >= 0) {
                    ByteBuffer b = vEnc.getOutputBuffer(i);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && b != null) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        info.presentationTimeUs -= originUs;
                        write(true, b, info);
                    }
                    vEnc.releaseOutputBuffer(i, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Throwable t) {
            abort("Rekam berhenti: " + t.getClass().getSimpleName());
        }
    }

    private static final float MIC_GAIN = 1.4f;

    private void audioLoop() {
        byte[] g = new byte[4096];
        byte[] m = new byte[4096];
        long frames = 0;
        long baseUs = -1;
        try {
            if (aRec != null) aRec.startRecording();
            if (mRec != null) mRec.startRecording();
            while (!stopFlag) {
                int ng = 0, nm = 0;
                if (aRec != null) ng = Math.max(0, aRec.read(g, 0, g.length));
                if (mRec != null) nm = Math.max(0, mRec.read(m, 0, m.length));
                int n = Math.max(ng, nm);
                if (n <= 0) continue;
                n &= ~3;
                if (n <= 0) continue;
                // Campur suara game dan mikrofon (jumlahkan, dibatasi supaya tidak pecah).
                for (int i = 0; i + 1 < n; i += 2) {
                    int sg = i + 1 < ng ? (short) ((g[i] & 0xFF) | (g[i + 1] << 8)) : 0;
                    int sm = i + 1 < nm ? (short) ((m[i] & 0xFF) | (m[i + 1] << 8)) : 0;
                    int mixed = aRec != null && mRec != null ? sg + Math.round(sm * MIC_GAIN)
                            : (aRec != null ? sg : Math.round(sm * MIC_GAIN));
                    if (mixed > 32767) mixed = 32767;
                    if (mixed < -32768) mixed = -32768;
                    g[i] = (byte) (mixed & 0xFF);
                    g[i + 1] = (byte) ((mixed >> 8) & 0xFF);
                }
                if (baseUs < 0) baseUs = System.nanoTime() / 1000L - originUs - (n / 4L) * 1_000_000L / RATE;
                int idx = aEnc.dequeueInputBuffer(10_000);
                if (idx >= 0) {
                    ByteBuffer ib = aEnc.getInputBuffer(idx);
                    ib.clear();
                    ib.put(g, 0, n);
                    aEnc.queueInputBuffer(idx, 0, n, baseUs + frames * 1_000_000L / RATE, 0);
                    frames += n / 4;
                }
                drainAudio(false);
            }
            int idx = aEnc.dequeueInputBuffer(100_000);
            if (idx >= 0) {
                aEnc.queueInputBuffer(idx, 0, 0, baseUs + frames * 1_000_000L / RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drainAudio(true);
        } catch (Throwable t) {
            // Suara gagal di tengah jalan: video tetap disimpan.
        } finally {
            try { if (aRec != null) aRec.stop(); } catch (Throwable ignored) { }
            try { if (mRec != null) mRec.stop(); } catch (Throwable ignored) { }
        }
    }

    private void drainAudio(boolean untilEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long until = System.currentTimeMillis() + 2000;
        while (true) {
            int i = aEnc.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, aEnc.getOutputFormat());
            } else if (i >= 0) {
                ByteBuffer b = aEnc.getOutputBuffer(i);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && b != null) {
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    write(false, b, info);
                }
                aEnc.releaseOutputBuffer(i, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            } else if (!untilEos) {
                return;
            } else if (System.currentTimeMillis() > until) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------ selesai

    private void abort(String msg) {
        if (finishing.getAndSet(true)) return;
        stopFlag = true;
        releaseAll(false);
        active = false;
        stopped(false, msg);
        showForeground(false, false);
    }

    /** Hentikan rekaman, simpan file. Memblokir. Mengembalikan true bila file tersimpan. */
    private boolean finishCore() {
        if (finishing.getAndSet(true)) return false;
        stopFlag = true;
        try { if (vEnc != null && active) vEnc.signalEndOfInputStream(); } catch (Throwable ignored) { }
        try { if (vThread != null) vThread.join(4000); } catch (InterruptedException ignored) { }
        try { if (aThread != null) aThread.join(4000); } catch (InterruptedException ignored) { }
        boolean saved = muxStarted;
        releaseAll(true);
        active = false;
        return saved;
    }

    /** Berhenti merekam; izin rekam tetap dipegang untuk rekaman berikutnya. */
    private void finishRecording() {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean saved = finishCore();
                stopped(saved, saved ? "Rekaman tersimpan" : "Rekaman kosong");
                if (armed) showForeground(false, false);
            }
        }).start();
    }

    /** Lepaskan semuanya (akhir sesi game). */
    private void disarm() {
        new Thread(new Runnable() {
            @Override public void run() {
                if (active) {
                    boolean saved = finishCore();
                    stopped(saved, saved ? "Rekaman tersimpan" : "Rekaman kosong");
                }
                armed = false;
                MediaProjection p = projection;
                projection = null;
                if (p != null) {
                    try { p.unregisterCallback(projCb); } catch (Throwable ignored) { }
                    try { p.stop(); } catch (Throwable ignored) { }
                }
                fgOn = false;
                stopForeground(true);
                stopSelf();
            }
        }).start();
    }

    private void releaseAll(boolean keep) {
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        display = null;
        try { if (vEnc != null) { vEnc.stop(); vEnc.release(); } } catch (Throwable ignored) { }
        try { if (aEnc != null) { aEnc.stop(); aEnc.release(); } } catch (Throwable ignored) { }
        try { if (aRec != null) aRec.release(); } catch (Throwable ignored) { }
        try { if (mRec != null) mRec.release(); } catch (Throwable ignored) { }
        vEnc = null;
        aEnc = null;
        aRec = null;
        mRec = null;
        synchronized (lock) {
            try { if (muxer != null && muxStarted) muxer.stop(); } catch (Throwable ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            muxer = null;
        }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        pfd = null;
        if (outUri != null) {
            try {
                if (keep && muxStarted) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, v, null, null);
                } else {
                    getContentResolver().delete(outUri, null, null);
                }
            } catch (Throwable ignored) { }
        } else if (outFile != null && !(keep && muxStarted)) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        armed = false;
        super.onDestroy();
    }
}
