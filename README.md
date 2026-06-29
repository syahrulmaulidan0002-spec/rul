# RUL - Absensi (Scaffold)

Scaffold awal untuk website absensi real-time menggunakan Next.js + Tailwind + Firebase.

Setup singkat:

1. Install dependencies

   npm install

2. Buat file environment dari contoh:

   cp .env.local.example .env.local

3. Isi konfigurasi Firebase di .env.local (lihat variabel di bawah)

4. Jalankan dev server

   npm run dev

Environment variables (.env.local):

NEXT_PUBLIC_FIREBASE_API_KEY=
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=
NEXT_PUBLIC_FIREBASE_PROJECT_ID=
NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=
NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=
NEXT_PUBLIC_FIREBASE_APP_ID=

Catatan: jangan commit kredensial nyata ke repo. Gunakan secrets di CI/CD.
