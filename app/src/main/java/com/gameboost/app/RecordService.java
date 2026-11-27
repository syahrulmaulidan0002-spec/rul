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

    static final String ACTION_START = "gb.rec.start";
    static final String ACTION_STOP = "gb.rec.stop";
    static final String EX_CODE = "code";
    static final String EX_DATA = "data";
    static final String EX_GAME = "game";
    static final String EX_MIC = "mic";
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

    static volatile boolean active = false;
    static volatile long startedAt = 0;
    static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void stop(Context c) {
        if (active) c.startService(new Intent(c, RecordService.class).setAction(ACTION_STOP));
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

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            finish();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        final boolean wantGame = intent.getBooleanExtra(EX_GAME, false);
        final boolean wantMic = intent.getBooleanExtra(EX_MIC, false);
        showForeground(wantMic);
        final int code = intent.getIntExtra(EX_CODE, 0);
        final Intent data = intent.getParcelableExtra(EX_DATA);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    begin(code, data, wantGame, wantMic);
                } catch (Throwable t) {
                    abort("Rekam gagal: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                }
            }
        }).start();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void showForeground(boolean mic) {
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
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("Merekam layar")
                .setContentText("Ketuk untuk berhenti").setContentIntent(stop).setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ------------------------------------------------------------------------------ mulai

    private void begin(int code, Intent data, boolean wantGame, boolean wantMic) throws Exception {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("izin ditolak");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { finish(); }
        }, new Handler(Looper.getMainLooper()));

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
        vEnc = MediaCodec.createEncoderByType("video/avc");
        vEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
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
        stopForeground(true);
        stopSelf();
    }

    private void finish() {
        if (finishing.getAndSet(true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                stopFlag = true;
                try { if (vEnc != null && active) vEnc.signalEndOfInputStream(); } catch (Throwable ignored) { }
                try { if (vThread != null) vThread.join(4000); } catch (InterruptedException ignored) { }
                try { if (aThread != null) aThread.join(4000); } catch (InterruptedException ignored) { }
                boolean saved = muxStarted;
                releaseAll(true);
                active = false;
                stopped(saved, saved ? "Rekaman tersimpan" : "Rekaman kosong");
                stopForeground(true);
                stopSelf();
            }
        }).start();
    }

    private void releaseAll(boolean keep) {
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (vEnc != null) { vEnc.stop(); vEnc.release(); } } catch (Throwable ignored) { }
        try { if (aEnc != null) { aEnc.stop(); aEnc.release(); } } catch (Throwable ignored) { }
        try { if (aRec != null) aRec.release(); } catch (Throwable ignored) { }
        try { if (mRec != null) mRec.release(); } catch (Throwable ignored) { }
        synchronized (lock) {
            try { if (muxer != null && muxStarted) muxer.stop(); } catch (Throwable ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            muxer = null;
        }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
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
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        active = false;
        super.onDestroy();
    }
}
