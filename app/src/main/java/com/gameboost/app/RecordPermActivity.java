package com.gameboost.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** Layar bening sesaat: minta izin suara + izin rekam layar, lalu menyalakan RecordService. */
public class RecordPermActivity extends Activity {

    private static final int REQ_AUDIO = 1;
    private static final int REQ_PROJ = 2;
    private boolean wantGame, wantMic, audioOk = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        wantGame = Prefs.get(this).getBoolean(RecordService.PREF_GAME, true);
        wantMic = Prefs.get(this).getBoolean(RecordService.PREF_MIC, false);
        if (ShizukuHelper.ready()) {
            // Beri izin lewat Shizuku supaya dialog sistem "Mulai sekarang" dan izin suara tidak muncul
            // di atas game. Dijalankan di thread lain, lalu lanjut.
            new Thread(new Runnable() {
                @Override public void run() {
                    ShizukuHelper.exec("appops set " + getPackageName() + " PROJECT_MEDIA allow\n"
                            + "pm grant " + getPackageName() + " android.permission.RECORD_AUDIO\n");
                    runOnUiThread(new Runnable() { @Override public void run() { checkAudio(); } });
                }
            }).start();
        } else {
            checkAudio();
        }
    }

    private void checkAudio() {
        if (isFinishing()) return;
        if (!wantGame && !wantMic) {
            askProjection();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            audioOk = true;
            askProjection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        audioOk = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        askProjection();
    }

    private void askProjection() {
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
        } catch (Exception e) {
            fail("Tidak bisa meminta izin rekam");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJ) return;
        if (res == RESULT_OK && data != null) {
            Intent i = new Intent(this, RecordService.class).setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EX_CODE, res).putExtra(RecordService.EX_DATA, data)
                    .putExtra(RecordService.EX_GAME, wantGame && audioOk)
                    .putExtra(RecordService.EX_MIC, wantMic && audioOk);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            finish();
        } else {
            fail("Izin rekam ditolak");
        }
    }

    private void fail(String msg) {
        RecordService.Listener l = RecordService.listener;
        if (l != null) l.onStopped(false, msg);
        finish();
    }
}
