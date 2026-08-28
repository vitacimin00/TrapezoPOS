# Trapezo POS

Offline-first Android Point of Sale untuk retail/minimarket, dibangun dengan Kotlin, Jetpack Compose, dan Room menggunakan package `com.trapezo.pos`.

## Status baseline

Core aplikasi mencakup POS, produk, stok, transaksi/refund, customer, shift/kas, laporan, import/export Excel, Bluetooth thermal printer, barcode scanner, serta backup/restore SQLite lokal.

Baseline core terbaru menambahkan hardening sebelum pekerjaan UI lanjutan:

- refund memakai nilai final transaksi setelah diskon, pajak, service charge, dan rounding;
- refund memiliki ledger metode pembayaran dan ikut mengoreksi kas shift;
- laporan menghitung net sales berdasarkan waktu sale/refund;
- install baru tidak memiliki username/password universal;
- login memiliki throttling persisten;
- refund, user management, dan perubahan master customer sensitif dibatasi ke admin aktif;
- minimal satu admin aktif selalu dipertahankan;
- hanya satu shift boleh berstatus `OPEN`, ditegakkan oleh transaksi repository dan constraint database;
- create/edit produk, SKU sequence, initial stock, stock adjustment, dan cash movement diproses secara transactional;
- signing secret tidak disimpan di repository;
- Android platform backup dinonaktifkan untuk database POS lokal;
- GitHub Actions menjalankan unit test dan debug build.

## Setup pertama

Pada database baru, aplikasi menampilkan **Setup pemilik**. Isi nama, username, dan password admin sendiri. Tidak ada lagi kredensial `admin/admin123` bawaan.

Database dari versi lama tetap mempertahankan user yang sudah ada. Migration menambahkan hardening tanpa destructive reset.

## Signing release

File `keystore.properties` dan file keystore tidak boleh di-commit. Salin template lokal:

```text
keystore.properties.example -> keystore.properties
```

Isi kredensial signing yang sebenarnya hanya di mesin build. Release signing hanya diaktifkan bila konfigurasi lokal lengkap.

> Kredensial signing yang pernah tersimpan di history repository harus dianggap terekspos. Rotasi password/keystore sebelum release produksi berikutnya.

## Data awal

Database baru hanya membuat:

- metode pembayaran Tunai, QRIS, Transfer, Debit, Kartu Kredit, E-Wallet, dan Lainnya;
- kategori `Lainnya`;
- row toko dan pengaturan POS/struk default.

User admin dibuat oleh pemilik melalui setup pertama. **Tidak ada produk, customer, transaksi, atau data contoh yang di-seed.**

## Alur utama

1. Lakukan setup pemilik pada install baru atau login pada database existing.
2. Buka shift di **Kasir** dan masukkan modal awal.
3. Tambahkan produk di **Produk** atau gunakan **Import Excel**.
4. Cari/scan produk pada Kasir.
5. Tambahkan customer atau diskon bila diperlukan.
6. Pilih pembayaran dan konfirmasi.
7. Sale, item financial snapshot, payment, stok, shift, dan audit disimpan atomik.
8. Cetak struk Bluetooth atau gunakan fallback PDF.
9. Refund diproses admin pada shift aktif dan dicatat sebagai financial movement terpisah.

## Financial integrity

- Semua nominal Rupiah disimpan sebagai `Long`.
- `sale_items.netTotal` menyimpan bagian final setiap line setelah alokasi diskon, pajak, service charge, dan rounding.
- Total seluruh `netTotal` line selalu sama dengan `sales.grandTotal`.
- Refund parsial memakai cumulative allocation agar tidak kehilangan/menambah Rp1 akibat pembulatan.
- Refund dibatasi oleh sisa quantity, sisa nilai transaksi, dan sisa kapasitas metode pembayaran asli.
- Refund CASH mengurangi `expectedCash` shift yang memproses refund.
- Laporan periodik menghitung sale sebagai nilai positif dan refund berdasarkan waktu refund sebagai nilai negatif.

## Shift & inventory integrity

- Database hanya mengizinkan satu shift `OPEN` melalui unique nullable `openGuard`.
- Migration memperbaiki database lama yang terlanjur memiliki duplicate OPEN dengan mempertahankan shift OPEN terbaru.
- Open shift, cash in/out, close shift, dan audit masing-masing berada dalam transaksi database yang sama.
- Checkout tetap memvalidasi stok dan shift di dalam write transaction.
- Stock adjustment membaca stok authoritative di dalam transaction sebelum menentukan movement aktual.
- Create produk + auto SKU + initial stock + inventory movement berada dalam satu transaction.

## Excel

- `.xlsx` diproses sebagai workbook OOXML asli, bukan CSV ber-ekstensi XLSX.
- **Produk → Download template** membuat workbook kosong dengan sheet `product` dan 49 header.
- Import melakukan validasi header, preview, dan kebijakan duplicate.
- Export mengambil produk lokal ke format workbook yang sama.

Semantik sebagian kolom workbook lanjutan masih masuk scope hardening berikutnya dan belum boleh dianggap sebagai round-trip penuh seluruh 49 field.

## Printer dan barcode

- Kamera barcode memakai CameraX + ML Kit.
- Scanner keyboard-wedge didukung melalui kolom pencarian Kasir.
- Printer thermal memakai Bluetooth Classic SPP/ESC-POS untuk device yang sudah dipasangkan.
- Printer/scanner fisik tetap perlu diuji pada hardware target sebelum production rollout.

## Backup / Restore

- Backup memakai Storage Access Framework dan checkpoint WAL sebelum database disalin.
- Restore memakai staging dan cadangan `.pre_restore` sebelum mengganti database aktif.
- Android platform backup dinonaktifkan, sehingga data POS tidak ikut backup Android generik.

Enkripsi backup dan validasi schema restore yang lebih ketat masih termasuk hardening lanjutan.

## Keamanan

- Password disimpan menggunakan PBKDF2-HMAC-SHA256 dengan random salt, bukan plaintext.
- Login failure dan cooldown disimpan di database sehingga restart aplikasi tidak menghapus throttling.
- Install baru menggunakan owner bootstrap, bukan password default universal.
- Refund diverifikasi lagi di repository sebagai aksi `ADMIN` aktif.
- Pengelolaan user diverifikasi lagi di repository dan tidak dapat menghasilkan zero active admin.
- Customer points/balance tidak dapat diubah langsung dari editor profil customer.
- Secret signing tidak boleh ada di Git.

## Known limitations

- QRIS masih manual confirmation, belum payment gateway.
- Cloud sync belum diimplementasikan; `SyncService` masih extension point.
- Backup file SQLite saat ini belum dienkripsi.
- Beberapa flow UI/pagination dan hardening Excel/image/printer/scanner masih masuk Track D-F berikutnya.

## Struktur modul

```text
com.trapezo.pos
├── data (database, dao, entity, repository)
├── domain (pricing, cart, payment, refund rules)
├── ui (login/setup, dashboard, pos, products, inventory, transactions, customers, reports, settings)
├── scanner (CameraX + ML Kit)
├── printer (ESC/POS, PDF, Bluetooth)
├── excel (OOXML reader/writer, import/export)
├── backup (SQLite backup/restore)
├── sync (cloud extension point)
└── utils
```

## Testing

Unit-test suite mencakup:

- `CartEngineTest`
- `PaymentAllocationTest`
- `RefundRulesTest`
- `PricingEngineTest`
- `XlsxModuleTest`
- `ExcelNumberParserTest`
- `PasswordUtilTest`

GitHub Actions menjalankan:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

## Android Studio

Buka root project `TrapezoPOS` di Android Studio dan gunakan JDK 17+ yang kompatibel dengan toolchain project.

Untuk debug build:

```bash
./gradlew assembleDebug
```

Untuk release build, sediakan `keystore.properties` lokal berdasarkan template sebelum menjalankan task release.

---

Trapezo POS is original software created for this project.
