# Gowes Offline — fondasi Android MVP

Proyek native Android/Kotlin berdasarkan `chatku.docx`. Implementasi awal mencakup layar utama, izin lokasi, perekaman GPS melalui foreground service, penyimpanan titik dan riwayat secara lokal, serta statistik jarak/durasi/kecepatan sederhana.

## Status penting
Ini adalah **fondasi MVP**, bukan implementasi lengkap seluruh dokumen. Peta offline, editor rute, deteksi keluar jalur, GPX, penyaringan GPS lanjutan, total tanjakan yang dihaluskan, dan kartu PNG/JPG belum tersedia.

## Build APK
1. Buka folder ini melalui Android Studio versi yang mendukung Android Gradle Plugin 8.7.x.
2. Pastikan Android SDK Platform 35 dan JDK 17 atau lebih baru terpasang.
3. Tunggu Gradle sync, lalu pilih **Build > Build APK(s)**.
4. APK debug biasanya berada di `app/build/outputs/apk/debug/app-debug.apk`.

Catatan: lingkungan tempat proyek ini dibuat tidak memiliki Flutter, Android SDK, Gradle, atau akses jaringan untuk mengunduh toolchain, sehingga APK/AAB tidak dapat dikompilasi atau diuji di sini. Proyek sumber disertakan agar dapat dibangun di Android Studio.
