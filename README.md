# Trapezo POS

Offline-first Android Point of Sale untuk retail/minimarket, dibangun sebagai produk original dengan package `com.trapezo.pos`.

## Build artifacts (final)

| File | Path | Ukuran |
|---|---|---|
| **TrapezoPOS.apk** (release, signed) | `C:\Users\Nadia\TrapezoPOS\TrapezoPOS.apk` | ~39 MB |
| Source code | `C:\Users\Nadia\TrapezoPOS\TrapezoPOS_Source.zip` | ~103 KB |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | ~47 MB |

SHA-256 release APK: `2b1b5ef876c7baa68221b804e28d8d605b23a53e5ecc250bd46627e6a21e2076`

## Known issues (jujur)

- **Dialog form + keyboard di layar kecil**: pada beberapa kasus, dialog form (tambah produk / adjustment) bisa tertutup sendiri ketika keyboard muncul dan field yang ditap bergeser. Sudah ditambahkan `imePadding()` sebagai mitigasi; untuk pengalaman terbaik gunakan tablet atau landscape saat input form panjang.
- **Cetak Bluetooth & scanner kamera**: kode lengkap (ESC/POS + CameraX/ML Kit) tetapi belum diuji dengan printer/kamera fisik. Fallback Bagikan PDF tersedia dan berfungsi.
- **QRIS**: konfirmasi manual (bukan API gateway).
- **Cloud sync**: belum diimplementasikan (interface `SyncService` tersedia untuk ekspansi).

## Login pertama

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | `ADMIN` |

Ubah password admin dari **Settings → Kelola User** sebelum digunakan di toko.

## Data awal

Aplikasi hanya membuat:

- satu admin default;
- metode pembayaran Tunai, QRIS, Transfer, Debit, Kartu Kredit, E-Wallet, dan Lainnya;
- kategori `Lainnya`;
- pengaturan toko/struk default.

**Tidak ada produk, customer, transaksi, atau data dari file Excel referensi yang disertakan.**

## Alur utama

1. Login.
2. Buka shift di **Kasir** dan masukkan modal awal.
3. Tambahkan produk di **Produk** atau gunakan **Import Excel**.
4. Pada Kasir, cari produk, scan kamera barcode, atau masukkan barcode dari scanner eksternal keyboard-wedge lalu tekan Enter.
5. Tambahkan customer/diskon bila diperlukan.
6. Pilih pembayaran, lalu konfirmasi.
7. Sistem menyimpan sale, item snapshot, pembayaran, adjustment stok, shift, dan audit log secara atomik.
8. Cetak struk ke printer Bluetooth, atau bagikan struk PDF.

## Excel

- `.xlsx` diproses sebagai workbook OOXML asli, bukan CSV ber-ekstensi XLSX.
- **Produk → Download template** membuat workbook kosong dengan sheet `product` dan 49 header yang ditentukan.
- **Import** melakukan validasi header, preview 10 baris, menghitung valid/error/duplikat, lalu meminta pilihan kebijakan duplikat sebelum data masuk.
- Kebijakan duplikat default: **Skip**.
- Export mengambil produk aktif/nonaktif dari database lokal ke format `product` yang sama.

## Printer dan barcode

- Kamera barcode memakai CameraX + ML Kit dan meminta izin kamera ketika dipakai.
- Scanner barcode eksternal yang bertindak sebagai keyboard didukung: fokuskan kolom pencarian Kasir, scan, lalu scanner mengirim Enter.
- Printer thermal mendukung transport Bluetooth Classic SPP/ESC-POS untuk printer yang sudah dipasangkan dari Android Settings. Konfigurasi ada di **Settings → Printer thermal**.
- Printer fisik belum diuji pada perangkat ini; bila koneksi/cetak gagal, gunakan fallback **Bagikan PDF** yang tersedia di transaksi.

## Backup / Restore

- Backup memakai Storage Access Framework dan melakukan checkpoint WAL sebelum file SQLite disalin.
- Restore menyalin file ke staging, memvalidasi header SQLite, menyimpan database lama sebagai `.pre_restore`, lalu baru menempatkan backup baru.
- Setelah restore berhasil, tutup lalu buka kembali aplikasi.

## Notes keamanan dan batasan versi ini

- Password disimpan menggunakan PBKDF2-HMAC-SHA256, bukan plaintext.
- Semua nominal Rupiah disimpan sebagai `Long`.
- QRIS pada versi ini adalah **manual confirmation**, tanpa API gateway/merchant QRIS.
- `SyncService`/cloud belum diimplementasikan—aplikasi dirancang offline-first.
- Cetak Bluetooth dan scanner kamera perlu diuji pada perangkat Android nyata untuk memastikan kompatibilitas hardware tertentu.
- Tidak ada API, logo, database, endpoint, atau source code Olsera yang digunakan.

## Struktur modul

```
com.trapezo.pos
├── data (database, dao, entity, repository)
├── domain (model, usecase logic)
├── ui (login, dashboard, pos, products, inventory, transactions, customers, reports, settings)
├── scanner (CameraX + ML Kit)
├── printer (ESC/POS, PDF, Bluetooth)
├── excel (OOXML reader/writer, import/export service)
├── backup (SQLite backup/restore)
├── sync (SyncService interface untuk cloud masa depan)
└── utils (money, date, password, photo storage)
```

## Testing

6 unit test suite, semua lulus:

- `CartEngineTest` — aturan keranjang & stok
- `PaymentAllocationTest` — alokasi pembayaran & kembalian
- `RefundRulesTest` — aturan refund parsial/penuh
- `XlsxModuleTest` — workbook OOXML asli + template 49 kolom
- `ExcelNumberParserTest` — parser angka Indonesia/internasional
- `PasswordUtilTest` — hash PBKDF2 + kompatibilitas legacy

Smoke test di emulator Android API 36: login berhasil, dashboard tampil, semua modul dapat dinavigasi tanpa crash, database Room terbuat dengan seed yang benar (tanpa produk contoh).

## Android Studio

Buka folder root `TrapezoPOS` di Android Studio. Project memakai Kotlin, Jetpack Compose, Room, CameraX, ML Kit, dan Android SDK API 36.

Build dari terminal Git Bash pada Windows:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
/c/Users/Nadia/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat assembleRelease
```

---

Trapezo POS is original software created for this project.
