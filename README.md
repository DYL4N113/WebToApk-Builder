# WebToApk Builder 📱🚀

Aplikasi Android *Web-to-APK Builder* yang dibangun sepenuhnya dari nol. Aplikasi ini memungkinkan pengguna untuk mengubah file HTML lokal atau URL website menjadi aplikasi Android (APK) secara langsung di dalam perangkat Android, tanpa memerlukan komputer.

## ✨ Fitur Utama
- **Convert dari Folder HTML**: Ubah project web lokal (seperti game HTML5) menjadi APK.
- **Convert dari URL**: Bungkus website online menjadi aplikasi native.
- **Auto-Install**: Setelah APK berhasil dibuat, pengguna dapat langsung menginstalnya melalui dialog bawaan aplikasi.
- **Custom Icon**: Mendukung penggunaan ikon kustom untuk aplikasi yang dihasilkan.
- **Fullscreen WebView**: Aplikasi hasil build berjalan tanpa *title bar* yang mengganggu.

## 🛠️ Teknologi & Arsitektur
Proyek ini tidak menggunakan library *third-party* untuk proses *building*. Semuanya dijalankan secara *native* di dalam Android:
- **Bahasa**: Kotlin & Java
- **Build Engine**: Menjalankan binary asli `aapt2` (Android Asset Packaging Tool) dan `zipalign` yang dikompilasi khusus untuk arsitektur ARM64 (`jniLibs`).
- **Signing**: Menggunakan library `apksig` dari Google untuk menandatangani APK (V1, V2, V3) secara otomatis.
- **WebView Shell**: Menggunakan *template* `classes.dex` yang di-*generate* khusus untuk menjalankan file HTML lokal atau URL.

## 🚀 Cara Build Project Ini
1. Clone repository ini.
2. Buka di Android Studio atau VS Code.
3. Pastikan Anda memiliki Android SDK (API 34) dan JDK 17+.
4. Jalankan perintah:
   ```bash
   ./gradlew assembleDebug
Install APK ke perangkat Android:

bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
📂 Struktur Project
app/: Modul utama aplikasi (UI & Build Engine).

webview-shell/: Modul library yang di-compile menjadi template.dex untuk membungkus HTML/URL.

jniLibs/: Berisi binary aapt2 dan zipalign.

Dibuat oleh DYL4N sebagai bentuk eksplorasi tingkat lanjut dalam pengembangan aplikasi Android.