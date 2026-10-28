# GOES PRO (APLIKASI ANDROID UNTUK BERSEPEDA)

# RENCANA
- TUJUAN
bisa merekam rute dan mendapaatkan data total jarak, kecepatan, ketinggian/tanjakan, durasi dan lain sebagainya. saat bersepedaa tanpa menggunakan jaringan internet atau offline.

- CARA KERJA UNTUK MVP
buka aplikasi, aktifkan GPS walu tidak ada jaringan internet sama sekali, tekan tombol start goes, app menapillkan data secara langsung waktu, jarak, kecepatan, ketinggian, dan menggambar rute, jika sudah selesai stop atau finish, hasilnya bisa di download menjadi gambar png/jpg dengan ui gambar rute yang menonjol dengan background hitam polos dan data waktu, jarak, kecepatan, ketinggian/tanjakan dan lain sebagainya.

CATATAN: DATA BERGERAK SECARA REEL TIME, BERANIMASI (supaya tidak terlalu kaku), GRAFIK VISUALISASI DATA UNTUK ANALITIKNYA, UI MODEREN MINIMALIS DAN STRUKTUR YANG KUAT, DATA DISIMPAN DI LOKAL, SAYA MENGGUNAKAN GITHUB ACTION UNTUK BUILD APPNYA. SAYA MENGGUNAKAN ANDROID 8.1.0 PASTIKA BISA BERJALAN DI VERSI TERSEBUT ATAU LAINYA.

- FITUR SELANJUTNYA
BISA MEMBUAT RUTE,
BISA MELIHAT PETA BESERTA PERGERAKAN,

# STATUS MVP
- Aplikasi bisa merekam GPS di latar depan tanpa koneksi internet.
- Jarak, durasi, kecepatan rata-rata, tanjakan, dan jejak rute tampil selama rekaman.
- Riwayat perjalanan disimpan lokal di perangkat.
- Minimum Android 8.0 (API 26); kompatibel dengan Android 8.1 dan versi lebih baru.

# BUILD APK
Push ke branch `main` atau `master`, atau jalankan workflow **Build APK Gowes Offline** dari tab Actions.
Setelah workflow berhasil, unduh artifact `gowes-offline-apk` untuk mendapatkan `app-debug.apk`.
Untuk build lokal, gunakan JDK 17 dan Gradle 8.9, lalu jalankan `gradle assembleDebug`.

Saat pertama kali merekam, izinkan akses lokasi presisi dan aktifkan GPS perangkat. Rekaman tetap berjalan saat layar mati melalui notifikasi foreground service.
