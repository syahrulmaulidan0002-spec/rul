# GameBoost

> Dibuat oleh **Syahrul Maulidan**

Aplikasi Android (Java, tanpa library tambahan) untuk **mode game eksklusif**: saat dinyalakan,
aplikasi latar belakang ditutup, notifikasi dihapus, HP dihening-kan, dan hanya game yang berjalan.
Tampilan bergaya iOS (mode gelap) dengan jendela mengambang yang bisa digeser dan mengecil jadi pil.

Berjalan di Android 7.0 sampai Android 15/16 dan di HyperOS, MIUI, ColorOS, One UI, dll.

## Mengambil APK (tanpa Android Studio)

Setiap kali kode di-push, GitHub Actions membangun APK otomatis:

1. Buka tab **Releases** di repo, unduh `GameBoost.apk` dari rilis **GameBoost (terbaru)**.
   (Cadangan: tab **Actions** > build terakhir > Artifacts > `GameBoost-apk`.)
2. Pasang di HP. Izinkan "Pasang aplikasi dari sumber ini" jika diminta.

Untuk membangun sendiri: buka folder di Android Studio lalu **Run**, atau `gradle assembleDebug`
(Gradle 8.7, JDK 17).

## Shizuku: bekukan semua aplikasi (opsional, paling kuat)

Dengan [Shizuku](https://shizuku.rikka.app/), GameBoost mendapat hak shell (atau root bila Shizuku
dinyalakan lewat root) tanpa perlu root penuh. Hasilnya:

- **Bekukan**: aplikasi latar belakang di-`force-stop` lalu di-`suspend`, jadi tidak bisa hidup lagi
  dan tidak bisa membunyikan notifikasi sampai game selesai. Setelah selesai dipulihkan otomatis.
  Kalau ada yang tertinggal (ikon abu-abu), ketuk **Pulihkan semua** di layar utama.
- **Mode performa**: Game Mode "performa" untuk game (Android 12+), refresh rate layar dikunci ke
  maksimal, dan penghemat daya dimatikan selama bermain. Setelan semula dikembalikan.
  Pelindung suhu HP **tidak** disentuh.

Panduan langkah demi langkah ada di aplikasi (muncul saat pertama dibuka, atau lewat
**Panduan Shizuku & root** di bagian Izin). Ringkasnya:

- **Belum root**: pasang Shizuku → aktifkan Opsi pengembang → nyalakan Debugging nirkabel →
  Start di Shizuku (Pairing) → izinkan GameBoost. Setelah HP restart, ulangi bagian Start saja.
- **Sudah root**: pasang Shizuku → ketuk "Start (root)" → izinkan GameBoost.

Tanpa Shizuku, aplikasi tetap bekerja dengan cara biasa.

## Cara memakai

1. Buka GameBoost lalu atur bagian **IZIN** (ketuk barisnya):
   - **Jendela mengambang**: izin "Tampil di atas aplikasi lain".
   - **Akses Jangan Ganggu**: beri akses GameBoost.
   - **Akses notifikasi**: agar notifikasi bisa dihapus otomatis.
   - **Notifikasi mode game** (Android 13+), **Tanpa batasan baterai**, dan **izin latar belakang
     khusus merek** (Autostart di HyperOS dll.).
2. Pilih game dari daftar (game terdeteksi tampil paling atas, berlabel `GAME`).
3. Tekan **Mulai Mode Game**.

## Yang dilakukan mode game

- **Tutup semua aplikasi latar belakang**, lalu **diulang tiap 20 detik** karena banyak aplikasi
  hidup lagi sendiri. Game, launcher, keyboard, telepon, SMS, Layanan Google, dan komponen sistem
  tidak disentuh.
- **Hapus semua notifikasi yang masuk** (lewat akses notifikasi) dan bersihkan yang sudah ada.
- **Hening total**: Jangan Ganggu mode "Alarm saja", jadi notifikasi, panggilan, dan pesan tidak
  berbunyi atau muncul pop-up. Setelan semula dikembalikan saat mode berakhir.
- **Layar tetap menyala** selama jendela mengambang tampil.
- **Jendela mengambang**: cincin RAM, suhu, baterai, tombol **Bersihkan** dan **Selesai**. Seret
  untuk memindahkan, ketuk **−** untuk menjadi pil kecil yang menempel ke tepi layar.

## Batasan yang jujur

- Shizuku harus dinyalakan ulang setiap HP restart (kecuali dinyalakan lewat root). Beberapa HP,
  terutama HyperOS, butuh izin tambahan seperti "Debugging USB (pengaturan keamanan)".
- Mode performa tidak bisa memaksa FPS melewati batas game dan chipset. Yang dilakukan hanya
  menyingkirkan penghambat supaya FPS lebih stabil.
- Aplikasi tidak bisa menambah kekuatan chipset atau GPU. Hasilnya RAM lebih lega dan tanpa
  gangguan, jadi FPS biasanya naik kecil sampai sedang dan lebih stabil.
- **Tanpa root dan tanpa Shizuku**, Android hanya mengizinkan `killBackgroundProcesses`: aplikasi yang sedang di
  latar belakang berhenti, tetapi tidak bisa dipaksa mati total. Di Android 15 pun sebagian
  proses sistem tetap berjalan. **Dengan root**, GameBoost memakai `am force-stop` yang lebih tuntas.
- Notifikasi yang bersifat *ongoing* (misalnya pemutar musik) tidak bisa dihapus oleh aplikasi biasa.
- **Panggilan masuk juga dibisukan** selama mode game karena memakai "Alarm saja".
- **Android 13+**: karena APK dipasang di luar Play Store, "Akses notifikasi" bisa abu-abu. Buka Info
  Aplikasi GameBoost, ketuk titik tiga kanan atas, pilih **Izinkan setelan terbatas**.
- **HyperOS/MIUI**: nyalakan Autostart, set Hemat baterai ke "Tanpa batasan", dan aktifkan
  "Tampilkan pop-up saat berjalan di latar belakang". Tanpa ini sistem bisa mematikan GameBoost.
- Jendela mengambang tidak bisa menampilkan FPS game lain (Android tidak mengizinkannya tanpa akses
  khusus). Opsi **jendela kecil** hanya jalan di HP yang mendukung mode freeform.
- APK dari GitHub Actions bertanda tangan *debug*: bisa dipasang, tetapi bukan untuk Play Store.

## Struktur

```
app/src/main/java/com/gameboost/app/
  MainActivity.java       layar utama, izin, pilih game, tombol mulai/hentikan
  GameService.java        layanan foreground, jendela mengambang, penjaga tutup-ulang 20 detik
  GameNotifService.java   penghapus notifikasi selama mode game
  GameBooster.java        tutup/bekukan aplikasi, mode performa, Jangan Ganggu, root opsional
  GuideActivity.java      panduan langkah demi langkah (Shizuku, root, cara pakai)
  ShizukuHelper.java      status, izin, dan perintah shell lewat Shizuku
  ShellService.java       layanan shell yang dijalankan Shizuku
  VendorHelper.java       pintasan Autostart/baterai untuk HyperOS, ColorOS, dll.
  RingView.java           cincin progres RAM
  SysInfo.java            RAM, suhu/baterai, saran grafis
  GameAdapter.java        daftar game
  GameItem.java           data satu aplikasi
.github/workflows/build.yml   membangun APK otomatis
```
