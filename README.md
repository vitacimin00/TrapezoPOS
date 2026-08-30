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
- signing secret tidak disimpan di repository, dan packaging release gagal tertutup tanpa keystore produksi;
- Android platform backup dan transfer device-to-device dinonaktifkan untuk data POS lokal;
- backup portabel memakai paket `.trpz` berisi database + foto produk + logo toko;
- release build memakai R8 dan resource shrinking;
- GitHub Actions menjalankan unit test, debug build, build instrumentation, lint release, dan build release minified.

## Setup pertama

Pada database baru, aplikasi menampilkan **Setup pemilik**. Isi nama, username, dan password admin sendiri. Tidak ada lagi kredensial `admin/admin123` bawaan.

Database dari versi lama tetap mempertahankan user yang sudah ada. Migration menambahkan hardening tanpa destructive reset.

## Signing release

File `keystore.properties` dan file keystore tidak boleh di-commit. Salin template lokal:

```text
keystore.properties.example -> keystore.properties
```

Isi kredensial signing yang sebenarnya hanya di mesin build.

**Release packaging fail closed.** Task `assembleRelease`, `bundleRelease`, dan `packageRelease`
akan GAGAL bila `keystore.properties` tidak lengkap, masih berisi `CHANGE_ME`, atau file keystore
tidak ada, dengan pesan:

```text
Production release signing belum dikonfigurasi. Isi keystore.properties dengan keystore produksi sebelum membuat APK/AAB release.
```

Task analisis release yang tidak menghasilkan artefak (`lintRelease`, `testReleaseUnitTest`) tetap
bisa dijalankan tanpa secret produksi.

> Kredensial signing yang pernah tersimpan di history repository harus dianggap terekspos.
> **Jangan pakai ulang kredensial lama itu.** Rotasi keystore/password sebelum release produksi.

CI hanya memakai keystore **ephemeral** yang dibuat `keytool` di runner untuk membuktikan jalur
R8/signing bisa dipaketkan; keystore produksi tidak pernah dibuat atau disimpan di CI, dan APK/AAB
hasil CI tidak diunggah sebagai artefak produksi.

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

Format backup normal sekarang adalah **paket `.trpz`** (kontainer ZIP versioned, dibuat dengan
standard library saja):

```text
manifest.properties          format=TRAPEZO_POS_BACKUP, formatVersion=1, schemaVersion=5, createdAt
database/trapezo_pos.db      database Room (WAL di-checkpoint sebelum disalin)
media/product_photos/<file>  foto produk yang direferensikan database
media/store_media/<file>     logo toko yang direferensikan database
```

- Paket membawa database **beserta** foto produk dan logo toko yang direferensikan, sehingga
  restore tidak lagi kehilangan media.
- Backup/restore memakai Storage Access Framework.
- Restore mendeteksi format dari **isi file**, bukan ekstensi: paket `.trpz` maupun backup lama
  berupa file SQLite mentah (`.db`) tetap bisa dipulihkan.
- Backup historis dengan schema v1/v2 diterima dan dimigrasikan ke v5.
- Restore melakukan staging + validasi (magic SQLite, `user_version`, `application_id`,
  `quick_check`, tabel inti sesuai versi schema) sebelum menyentuh data aktif.
- Path media di database ditulis ulang ke direktori milik instalasi saat ini.
- Arsip ditolak bila mengandung path traversal, entri absolut, entri duplikat, entri tak dikenal,
  manifest hilang, `formatVersion` tak didukung, atau melewati batas ukuran/dekompresi.
- Kegagalan restore memakai rollback berbasis kepemilikan: resource yang belum berhasil
  dipindahkan tidak pernah dihapus, dan pemulihan yang tidak tuntas dilaporkan eksplisit
  (bukan diklaim berhasil).
- **Restore memaksa autentikasi ulang.** Database hasil restore bisa punya user/role/kredensial
  berbeda, jadi sesi lama selalu dibuang dan layar login/setup ditampilkan.
- Bila database lokal tidak bisa dibuka saat startup, aplikasi menampilkan layar pemulihan
  (`Coba Lagi` / `Pulihkan Backup`) memakai BackupService yang sama — tanpa crash, tanpa reset
  destruktif.
- Android platform backup dinonaktifkan (`allowBackup=false`) dan diperkuat oleh
  `backup_rules.xml` + `data_extraction_rules.xml` yang mengecualikan seluruh data POS dari cloud
  backup maupun transfer device-to-device.

> **Paket `.trpz` belum dienkripsi.** File berisi data bisnis; simpan hanya di lokasi yang Anda
> percaya. Enkripsi backup portabel adalah keputusan hardening terpisah setelah Track H.

## Keamanan

- Password disimpan menggunakan PBKDF2-HMAC-SHA256 dengan random salt, bukan plaintext.
- Work factor saat ini **600.000 iterasi** (naik dari 120.000). Hash lama tetap valid: verifikasi
  memakai iterasi yang tersimpan di record, lalu login sukses menulis ulang hash ke work factor
  terbaru. Tidak ada user existing yang di-invalidate.
- Login failure dan cooldown disimpan di database sehingga restart aplikasi tidak menghapus throttling.
- Install baru menggunakan owner bootstrap, bukan password default universal.
- Refund diverifikasi lagi di repository sebagai aksi `ADMIN` aktif.
- Pengelolaan user diverifikasi lagi di repository dan tidak dapat menghasilkan zero active admin.
- Customer points/balance tidak dapat diubah langsung dari editor profil customer.
- Secret signing tidak boleh ada di Git.
- Tidak ada permission `INTERNET` / `ACCESS_NETWORK_STATE` pada APK rilis. Keduanya dihapus dari
  merged manifest (`tools:node="remove"`) karena hanya ikut terbawa dependency telemetry ML Kit;
  aplikasi memang tidak melakukan network I/O.
- FileProvider hanya mengekspos dua path yang benar-benar dipakai: `cache/receipts/` (share PDF
  struk, read-only) dan `files/product_photos/` (target capture kamera).

## Release build

- `isMinifyEnabled = true` dan `isShrinkResources = true` (R8 + resource shrinking).
- `proguard-android-optimize.txt` + `proguard-rules.pro`, tanpa keep rule global seperti
  `-keep class com.trapezo.** { *; }`.
- `android.enableR8.fullMode=false` — **known release limitation.** R8 full mode terbukti
  membuat layar scanner crash pada siklus buka/tutup pertama di build minified
  (`NullPointerException: Object.getClass() on a null object reference`, jejak:
  `BarcodeScannerScreenKt$$ExternalSyntheticLambda6` → `DisposableEffectImpl` →
  `DaggerCameraPipeComponent$Camera2ControllerComponentImpl` → `material3.DatePickerKt`).
  Diuji ulang pada source terkini — yaitu **sesudah** perbaikan race disposal scanner — memakai
  APK yang baru dibuild dan diverifikasi hash-nya; crash tetap terjadi. Retrace `mapping.txt`
  menunjukkan R8 menggabungkan lambda `onDispose` scanner ke satu synthetic dispatch class
  (`$r8$classId`) bersama lambda Material3 DatePicker yang tidak berkaitan, sehingga sebuah
  capture field bernilai null pada cabang yang dieksekusi dan null-check implisit melempar
  exception sebelum `try/catch` milik `onDispose` sendiri dijalankan.
  Satu keep rule sempit yang didukung sudah dicoba
  (`-keep,allowshrinking,allowobfuscation class ...BarcodeScannerScreenKt** { *; }`): diterima
  R8 tetapi tidak efektif — `mapping.txt` membuktikan lambda scanner masih tergabung dan crash
  tetap muncul — jadi rule itu dihapus, bukan dibiarkan. Compatibility mode tetap menjalankan
  shrinking, optimization, dan obfuscation.
- Room schema saat ini **versi 5** (tanpa destructive migration).

## Known limitations

- QRIS masih manual confirmation, belum payment gateway.
- Cloud sync belum diimplementasikan; `SyncService` masih extension point. Tidak ada permission
  `INTERNET` — ini disengaja.
- Paket backup `.trpz` belum dienkripsi.
- **Printer thermal Bluetooth belum diuji pada hardware fisik.** Jalur ESC/POS SPP masih harus
  melewati acceptance test di printer nyata sebelum rollout produksi.
- **PBKDF2 TARGET-HARDWARE DECISION: PENDING — work factor produksi BELUM difinalkan.**
  Login memakai PBKDF2-HMAC-SHA256 600.000 iterasi. Pada emulator x86_64 (`Medium_Phone`,
  API 36) satu operasi hash/verify butuh ~5,7 detik (median 5 sampel: 5648/5665/5706/5864/6061 ms;
  login teramati ~6,7 s). Angka emulator x86_64 **tidak sah** sebagai angka UX produksi dan
  tidak dipakai untuk menurunkan iterasi. Benchmark ARM pada perangkat target: **NOT AVAILABLE**.
  Iterasi 600.000 dipertahankan apa adanya sampai pengukuran perangkat nyata tersedia.
- Beberapa semantik kolom lanjutan workbook Excel belum bisa dianggap round-trip penuh 49 field.

## Governance sebelum tag produksi

Branch `main` saat ini belum diproteksi. Sebelum tag/release produksi, konfigurasi berikut harus
diaktifkan dan direview oleh auditor eksternal (di luar source code aplikasi):

- `main` wajib lewat pull request;
- Android CI wajib hijau sebagai required status check;
- force push dinonaktifkan;
- penghapusan branch dinonaktifkan.

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

Unit test (JVM) mencakup antara lain:

- `CartEngineTest`, `PricingEngineTest`, `PaymentAllocationTest`, `PaymentDraftTest`
- `RefundRulesTest`, `RefundPreviewTest`, `MoneyOverflowTest`
- `XlsxModuleTest`, `ExcelNumberParserTest`, `OperationalInputRulesTest`
- `PasswordUtilTest` (work factor 600k, kompatibilitas hash 120k, penolakan record rusak)
- `BackupPackageTest` (batas arsip, entri duplikat, traversal, batas writer)
- `StoreLogoContainmentTest`, `UiPresentationTest`, `ReceiptTextSafetyTest`

Instrumentation test (perangkat/emulator) mencakup antara lain:

- `MigrationTest` (1→2→3→4→5), `BackupRoundTripTest`, `G3PreprodTest`
- `ReleaseHardeningTest` (recovery startup, restore legacy `.db` fail-safe)
- `CheckoutConcurrencyTest`, `RefundConcurrencyTest`, `ConcurrencyTest`
- `RepositoryAuthorizationTest`, `AuthorizationTest`, `ProductExcelCategoryTest`

GitHub Actions menjalankan:

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --stacktrace
./gradlew lintRelease assembleRelease --stacktrace
```

Job CI membuat keystore **ephemeral** dengan `keytool` sebelum task release, lalu menghapusnya.
Lokal (dengan `keystore.properties` sendiri):

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest --stacktrace
./gradlew lintRelease assembleRelease bundleRelease --stacktrace
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
