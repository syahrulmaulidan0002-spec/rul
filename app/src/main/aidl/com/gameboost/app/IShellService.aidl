package com.gameboost.app;

// Layanan yang dijalankan Shizuku dengan hak shell (atau root). Menjalankan perintah dan
// mengembalikan keluarannya.
interface IShellService {
    // Nomor transaksi khusus yang dipakai Shizuku untuk menghentikan layanan.
    void destroy() = 16777114;

    String exec(String command) = 1;
}
