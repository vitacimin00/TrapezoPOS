package com.trapezo.pos.backup

import android.content.Context
import android.net.Uri
import com.trapezo.pos.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local backup/restore through Storage Access Framework streams.
 *
 * Track G3 introduces a versioned PACKAGE (.trpz): a ZIP-compatible container carrying the
 * Room database PLUS the managed product photos and store logo referenced by that database,
 * so a restore no longer loses media. Restore auto-detects and still accepts historical raw
 * SQLite (.db) backups.
 *
 * Snapshot safety order (unchanged Track A–E safeguards):
 *   1. serialize via [lock] (backup/restore never overlap);
 *   2. ensure the Trapezo `application_id` marker is set (idempotent);
 *   3. run a FULL checkpoint and *inspect* its result — a BUSY/incomplete checkpoint
 *      aborts the backup rather than producing a snapshot that misses the marker;
 *   4. close Room cleanly (AppDatabase.closeAndClear) so no SQLite handle can
 *      auto-checkpoint into the main file while bytes are being copied;
 *   5. copy the stable main database (+ referenced media) into the package;
 *   6. Room reopens lazily through AppGraph on the next access.
 *
 * Room owns `PRAGMA user_version`; we never set it manually. The Trapezo marker
 * is carried by `application_id` only.
 */
class BackupService(
    private val context: Context,
    /**
     * Minimal filesystem seam for the restore apply/rollback state machine.
     *
     * The production default performs real `File` operations. It exists ONLY so the data-safety
     * branches (secure-original succeeded / apply failed / rollback itself failed) can be driven
     * deterministically from tests — a rename failure cannot otherwise be provoked reliably. It is
     * NOT a debug switch: there is no runtime flag, no build-type behaviour, and production always
     * gets [FileOps.Default].
     */
    private val fileOps: FileOps = FileOps.Default
) {
    data class BackupResult(val ok: Boolean, val message: String, val fileName: String? = null)

    /** Filesystem operations used by the restore apply/rollback path. */
    interface FileOps {
        fun rename(from: File, to: File): Boolean
        fun delete(file: File): Boolean
        fun deleteRecursively(file: File): Boolean

        object Default : FileOps {
            override fun rename(from: File, to: File): Boolean = from.renameTo(to)
            override fun delete(file: File): Boolean = file.delete()
            override fun deleteRecursively(file: File): Boolean = file.deleteRecursively()
        }
    }

    companion object {
        /** "TRPZ" in big-endian — SQLite application_id marker for Trapezo POS backups. */
        internal const val APP_ID: Int = 0x5452505A

        /** Room schema version the app currently ships. */
        internal const val CURRENT_SCHEMA_VERSION = 5

        /** Operational cap for the database file itself. */
        private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024

        /** Cap for the total restore input stream (package: DB + media + overhead). */
        private const val MAX_PACKAGE_INPUT_BYTES = 1_100L * 1024 * 1024

        private val REQUIRED_TABLES = listOf(
            "users", "stores", "categories", "products", "product_barcodes",
            "inventory_movements", "customers", "sales", "sale_items", "payments",
            "payment_methods", "shifts", "cash_movements", "refunds", "refund_items",
            "refund_payments", "settings", "audit_logs"
        )
        private val lock = Any()
    }

    fun suggestedName(): String =
        "TrapezoPOS_Backup_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.trpz"

    /** Returns the checkpoint result as (busy, logPages, checkpointedPages). */
    private fun checkpointResult(db: androidx.sqlite.db.SupportSQLiteDatabase): Triple<Int, Int, Int> {
        db.query("PRAGMA wal_checkpoint(FULL)").use { c ->
            return if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2)) else Triple(1, 0, 0)
        }
    }

    private fun photoDir() = File(context.filesDir, "product_photos")
    private fun logoDir() = File(context.filesDir, "store_media")

    /** Resolves a DB-stored media path to a managed file, rejecting anything outside its dir. */
    private fun managedMediaFile(path: String, dir: File, prefix: String): File {
        val f = File(path)
        val canon = try { f.canonicalFile } catch (_: Exception) {
            throw IllegalArgumentException("Referensi media tidak valid")
        }
        val canonDir = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
        if (!f.name.startsWith(prefix) || !canon.path.startsWith(canonDir.path + File.separator)) {
            throw IllegalArgumentException("Referensi media tidak valid")
        }
        return f
    }

    /** Distinct referenced, existing managed files (throws when a referenced file is missing). */
    private fun gatherManagedFiles(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        dir: File,
        prefix: String,
        label: String
    ): List<File> {
        val found = LinkedHashSet<File>()
        var missing = 0
        db.query(sql).use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0)
                if (path.isNullOrBlank()) continue
                val f = managedMediaFile(path, dir, prefix)
                if (!f.exists()) missing++ else found.add(f)
            }
        }
        if (missing > 0) {
            throw IllegalArgumentException(
                "Backup tidak dapat dibuat karena $missing $label yang tercatat tidak ditemukan."
            )
        }
        return found.toList()
    }

    suspend fun backupTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            AppDatabase.withMaintenance { current -> try {
                val dbFile = context.getDatabasePath(AppDatabase.NAME)
                if (!dbFile.exists()) return@withMaintenance BackupResult(false, "Database belum tersedia")
                if (dbFile.length() > MAX_BACKUP_BYTES) return@withMaintenance BackupResult(false, "Database terlalu besar untuk dibackup")

                val db = current.openHelper.writableDatabase
                db.execSQL("PRAGMA application_id = $APP_ID")
                val (busy, logPages, checkpointed) = checkpointResult(db)
                if (busy != 0 || logPages != checkpointed) {
                    return@withMaintenance BackupResult(false, "Checkpoint tidak selesai (busy=$busy, $checkpointed/$logPages halaman); coba lagi saat tidak ada operasi")
                }

                // Gather referenced media BEFORE closing Room.
                val photos = gatherManagedFiles(
                    db, "SELECT photo FROM products WHERE photo IS NOT NULL AND photo != ''",
                    photoDir(), "product_", "foto produk"
                )
                val logos = gatherManagedFiles(
                    db, "SELECT logo FROM stores WHERE logo IS NOT NULL AND logo != ''",
                    logoDir(), "store_logo_", "logo toko"
                )

                AppDatabase.closeAndClear()

                // Re-open raw read-only and confirm the marker is durable before copying.
                val probe = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                val visible = probe.rawQuery("PRAGMA application_id", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                probe.close()
                if (visible != APP_ID) {
                    return@withMaintenance BackupResult(false, "Marker backup belum tertanam pada file utama")
                }

                val out = context.contentResolver.openOutputStream(uri)
                    ?: return@withMaintenance BackupResult(false, "Tidak bisa menulis file backup")
                out.use {
                    BackupPackage.write(out, dbFile, photos, logos, CURRENT_SCHEMA_VERSION, System.currentTimeMillis())
                }
                BackupResult(true, "Backup berhasil dibuat", suggestedName())
            } catch (e: Exception) {
                BackupResult(false, "Backup gagal: ${e.message}")
            } }
        }
    }

    suspend fun restoreFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            AppDatabase.withMaintenance {
                val live = context.getDatabasePath(AppDatabase.NAME)
                val parent = live.parentFile ?: return@withMaintenance BackupResult(false, "Folder database tidak tersedia")
                val staged = File(parent, "${AppDatabase.NAME}.restore_staged")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_PACKAGE_INPUT_BYTES) throw IllegalStateException("File backup melebihi batas ukuran")
                                out.write(buf, 0, n)
                            }
                        }
                    } ?: return@withMaintenance BackupResult(false, "Tidak dapat membaca file yang dipilih")

                    if (isRawSqlite(staged)) {
                        val r = restoreLegacy(staged, live)
                        staged.delete()
                        return@withMaintenance r
                    }
                    if (isZip(staged)) {
                        val r = restorePackage(staged, live)
                        staged.delete()
                        return@withMaintenance r
                    }
                    staged.delete()
                    return@withMaintenance BackupResult(false, "File bukan backup Trapezo POS yang valid")
                } catch (e: Exception) {
                    staged.delete()
                    BackupResult(false, "Restore gagal: ${e.message}")
                }
            }
        }
    }

    private fun isRawSqlite(f: File): Boolean {
        val header = ByteArray(16)
        f.inputStream().use { it.read(header) }
        return header.copyOfRange(0, 16).toString(Charsets.US_ASCII).startsWith("SQLite format 3")
    }

    private fun isZip(f: File): Boolean {
        val header = ByteArray(4)
        f.inputStream().use { it.read(header) }
        return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    /** Legacy raw-SQLite restore: DB only, media never included. */
    internal fun restoreLegacy(staged: File, live: File): BackupResult {
        if (staged.length() > MAX_BACKUP_BYTES) {
            return BackupResult(false, "File backup melebihi batas ukuran")
        }
        val error = validateStaged(staged)
        if (error != null) return BackupResult(false, error)

        val parent = live.parentFile ?: return BackupResult(false, "Folder database tidak tersedia")
        val previous = File(parent, "${AppDatabase.NAME}.pre_restore")
        val wal = File(parent, "${AppDatabase.NAME}-wal")
        val shm = File(parent, "${AppDatabase.NAME}-shm")

        // Same data-safety standard as the package path: an ownership ledger decides what
        // rollback is allowed to touch, and an incomplete recovery is reported honestly.
        val state = ApplyState(fileOps, live, previous)

        AppDatabase.closeAndClear()
        try {
            if (previous.exists() && !fileOps.delete(previous)) {
                throw IllegalStateException("Gagal membersihkan cadangan database sebelumnya")
            }
            if (live.exists()) {
                if (!fileOps.rename(live, previous)) throw IllegalStateException("Gagal mengamankan database lama")
                state.dbBackedUp = true
            }
            wal.delete(); shm.delete()

            if (!fileOps.rename(staged, live)) throw IllegalStateException("Gagal menerapkan database restore")
            state.newDbApplied = true

            fileOps.delete(previous)
            return BackupResult(
                true,
                "Backup database lama berhasil dipulihkan. Foto/logo dari backup lama hanya " +
                    "tersedia jika file medianya masih ada di perangkat."
            )
        } catch (e: Exception) {
            val unrecovered = state.rollback()
            return if (unrecovered.isEmpty()) {
                BackupResult(false, "Restore gagal dan data lama dikembalikan: ${e.message}")
            } else {
                BackupResult(
                    false,
                    "Restore gagal dan pemulihan data lama tidak selesai. Jangan gunakan aplikasi " +
                        "sampai data diperiksa. (${unrecovered.joinToString("; ")})"
                )
            }
        }
    }

    /** Package restore: unpack, validate, rebind media paths, then atomically apply. */
    private fun restorePackage(staged: File, live: File): BackupResult {
        val stagingDir = File(context.filesDir, "restore_stage")
        val content = try {
            BackupPackage.read(staged, stagingDir, MAX_BACKUP_BYTES)
        } catch (e: BackupPackage.ParseException) {
            return BackupResult(false, e.message ?: "Arsip backup tidak valid")
        }
        if (content.manifest.schemaVersion < 1 || content.manifest.schemaVersion > CURRENT_SCHEMA_VERSION) {
            stagingDir.deleteRecursively()
            return BackupResult(false, "Versi skema backup tidak kompatibel dengan aplikasi")
        }

        // Structural SQLite validation of the extracted database.
        val dbError = validateStaged(content.dbFile)
        if (dbError != null) {
            stagingDir.deleteRecursively()
            return BackupResult(false, dbError)
        }

        // Rebind media paths onto the CURRENT installation and verify archive completeness.
        try {
            rebindMediaPaths(content, stagingDir)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return BackupResult(false, e.message ?: "Arsip backup tidak lengkap")
        }

        // Apply with STATE-AWARE rollback across DB + both media buckets.
        val parent = live.parentFile ?: run { stagingDir.deleteRecursively(); return BackupResult(false, "Folder database tidak tersedia") }
        val previous = File(parent, "${AppDatabase.NAME}.pre_restore")
        val wal = File(parent, "${AppDatabase.NAME}-wal")
        val shm = File(parent, "${AppDatabase.NAME}-shm")
        val photoPre = File(context.filesDir, "product_photos.pre")
        val logoPre = File(context.filesDir, "store_media.pre")
        val pDir = photoDir()
        val lDir = logoDir()

        // Ownership ledger: rollback may only remove a live resource that we KNOW is the
        // staged-new copy, and may only report recovery when a previously secured copy was
        // actually put back. A resource that was never moved is still the ORIGINAL and must
        // never be deleted during rollback.
        val state = ApplyState(fileOps, live, previous, pDir, photoPre, lDir, logoPre)

        AppDatabase.closeAndClear()
        try {
            if (previous.exists() && !fileOps.delete(previous)) {
                throw IllegalStateException("Gagal membersihkan cadangan database sebelumnya")
            }
            if (live.exists()) {
                if (!fileOps.rename(live, previous)) throw IllegalStateException("Gagal mengamankan database lama")
                state.dbBackedUp = true
            }
            wal.delete(); shm.delete()

            photoPre.deleteRecursively()
            if (pDir.exists()) {
                if (!fileOps.rename(pDir, photoPre)) throw IllegalStateException("Gagal mengamankan foto produk lama")
                state.photoBackedUp = true
            }
            logoPre.deleteRecursively()
            if (lDir.exists()) {
                if (!fileOps.rename(lDir, logoPre)) throw IllegalStateException("Gagal mengamankan logo toko lama")
                state.logoBackedUp = true
            }

            if (!fileOps.rename(content.dbFile, live)) throw IllegalStateException("Gagal menerapkan database restore")
            state.newDbApplied = true

            val stagedPhotos = File(stagingDir, BackupPackage.PHOTO_DIR)
            val stagedLogos = File(stagingDir, BackupPackage.LOGO_DIR)
            if (stagedPhotos.exists()) {
                if (!fileOps.rename(stagedPhotos, pDir)) throw IllegalStateException("Gagal menerapkan foto produk")
                state.newPhotosApplied = true
            }
            if (stagedLogos.exists()) {
                if (!fileOps.rename(stagedLogos, lDir)) throw IllegalStateException("Gagal menerapkan logo toko")
                state.newLogosApplied = true
            }

            photoPre.deleteRecursively(); logoPre.deleteRecursively(); previous.delete()
            stagingDir.deleteRecursively()
            return BackupResult(true, "Restore berhasil. Data, foto produk, dan logo toko telah dipulihkan.")
        } catch (e: Exception) {
            val unrecovered = state.rollback()
            stagingDir.deleteRecursively()
            return if (unrecovered.isEmpty()) {
                BackupResult(false, "Restore gagal dan data lama dikembalikan: ${e.message}")
            } else {
                // NEVER claim a successful rollback when a rename-back actually failed.
                BackupResult(
                    false,
                    "Restore gagal dan pemulihan data lama tidak selesai. Jangan gunakan aplikasi " +
                        "sampai data diperiksa. (${unrecovered.joinToString("; ")})"
                )
            }
        }
    }

    /**
     * Ownership ledger for the restore apply phase, shared by the package and legacy raw-`.db`
     * paths.
     *
     * Invariant: never delete anything unless we know it is the staged-new copy, or we hold a
     * verified previous copy available to restore. A resource whose "secure the original" move
     * never succeeded is still the original and is left strictly untouched.
     *
     * The legacy path replaces only the database, so the media arguments are optional.
     */
    private class ApplyState(
        private val ops: FileOps,
        private val live: File,
        private val previous: File,
        private val photoDir: File? = null,
        private val photoPre: File? = null,
        private val logoDir: File? = null,
        private val logoPre: File? = null
    ) {
        /** True only when the ORIGINAL db was successfully moved aside to [previous]. */
        var dbBackedUp = false
        /** True only when the ORIGINAL product photos were successfully moved to [photoPre]. */
        var photoBackedUp = false
        /** True only when the ORIGINAL store media were successfully moved to [logoPre]. */
        var logoBackedUp = false
        /** True only when the staged-new db now occupies [live]. */
        var newDbApplied = false
        /** True only when staged-new photos now occupy [photoDir]. */
        var newPhotosApplied = false
        /** True only when staged-new store media now occupy [logoDir]. */
        var newLogosApplied = false

        /** Rolls back what we own. Returns descriptions of anything that could NOT be recovered. */
        fun rollback(): List<String> {
            val failed = mutableListOf<String>()

            // ---- database ----
            if (newDbApplied && live.exists() && !ops.delete(live)) {
                failed += "database hasil restore tidak dapat dihapus"
            }
            if (dbBackedUp) {
                if (live.exists() || !ops.rename(previous, live)) {
                    failed += "database lama tidak dapat dikembalikan"
                }
            }
            // !dbBackedUp && !newDbApplied -> `live` was never moved: it IS the original. Leave it.

            // ---- product photos ----
            if (photoDir != null && photoPre != null) {
                if (newPhotosApplied && photoDir.exists() && !ops.deleteRecursively(photoDir)) {
                    failed += "foto produk hasil restore tidak dapat dihapus"
                }
                if (photoBackedUp) {
                    if (photoDir.exists() || !ops.rename(photoPre, photoDir)) {
                        failed += "foto produk lama tidak dapat dikembalikan"
                    }
                }
            }

            // ---- store media ----
            if (logoDir != null && logoPre != null) {
                if (newLogosApplied && logoDir.exists() && !ops.deleteRecursively(logoDir)) {
                    failed += "logo toko hasil restore tidak dapat dihapus"
                }
                if (logoBackedUp) {
                    if (logoDir.exists() || !ops.rename(logoPre, logoDir)) {
                        failed += "logo toko lama tidak dapat dikembalikan"
                    }
                }
            }

            return failed
        }
    }

    /**
     * Rewrites the extracted DB's media paths to point at the CURRENT installation's managed
     * directories and verifies every referenced basename is actually present in the archive.
     */
    private fun rebindMediaPaths(content: BackupPackage.Content, stagingDir: File) {
        val pDir = photoDir(); val lDir = logoDir()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            content.dbFile.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            val photoUpdates = mutableListOf<Pair<Long, String>>()
            db.rawQuery("SELECT id, photo FROM products WHERE photo IS NOT NULL AND photo != ''", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val base = File(c.getString(1)).name
                    if (base.isBlank() || !content.photos.containsKey(base)) {
                        throw BackupPackage.ParseException("Arsip tidak lengkap: foto produk terreferensi hilang")
                    }
                    photoUpdates += id to File(pDir, base).absolutePath
                }
            }
            for ((id, newPath) in photoUpdates) {
                db.execSQL("UPDATE products SET photo=? WHERE id=?", arrayOf(newPath, id))
            }

            val logoUpdates = mutableListOf<Pair<Long, String>>()
            db.rawQuery("SELECT id, logo FROM stores WHERE logo IS NOT NULL AND logo != ''", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val base = File(c.getString(1)).name
                    if (base.isBlank() || !content.logos.containsKey(base)) {
                        throw BackupPackage.ParseException("Arsip tidak lengkap: logo toko terreferensi hilang")
                    }
                    logoUpdates += id to File(lDir, base).absolutePath
                }
            }
            for ((id, newPath) in logoUpdates) {
                db.execSQL("UPDATE stores SET logo=? WHERE id=?", arrayOf(newPath, id))
            }

            // Fold WAL into the main file so the rename applies the rewritten paths.
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } finally {
            try { db.close() } catch (_: Exception) { }
            // Drop any sidecar WAL/SHM beside the staged DB.
            File(content.dbFile.absolutePath + "-wal").delete()
            File(content.dbFile.absolutePath + "-shm").delete()
        }
    }

    /** Returns a user-facing error message, or null when the staged backup is valid. */
    internal fun validateStaged(staged: File): String? {
        if (!staged.exists() || staged.length() == 0L) return "File backup kosong"
        val header = ByteArray(16)
        staged.inputStream().use { input -> input.read(header) }
        val magic = header.copyOfRange(0, 16).toString(Charsets.US_ASCII)
        if (!magic.startsWith("SQLite format 3")) return "File bukan database SQLite yang valid"

        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(staged.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return "File backup rusak atau tidak dapat dibuka sebagai SQLite"
        }
        return try {
            fun pragmaInt(sql: String): Int =
                db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            val appId = pragmaInt("PRAGMA application_id")
            val userVersion = pragmaInt("PRAGMA user_version")

            // Marker validation with documented legacy-backup compatibility:
            //  - a marked (Track E+) backup must carry the Trapezo application_id;
            //  - a legacy (pre-Track-E) Trapezo backup has application_id=0 but a valid
            //    Room schema and full Trapezo table set, so it is still accepted.
            val isMarked = appId == APP_ID
            if (!isMarked && appId != 0) {
                return "File bukan backup Trapezo POS (penanda aplikasi tidak cocok)"
            }

            if (userVersion < 1 || userVersion > CURRENT_SCHEMA_VERSION) {
                return "Versi skema backup ($userVersion) tidak kompatibel dengan aplikasi ($CURRENT_SCHEMA_VERSION)"
            }

            // `refund_payments` is only created by migration 2→3: a genuine v1/v2 backup
            // must not be required to carry it. Everything else exists from v1.
            val required = if (userVersion < 3) REQUIRED_TABLES.filter { it != "refund_payments" } else REQUIRED_TABLES
            val tableCount = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN " +
                    "(${required.joinToString(",") { "'$it'" }})", null
            ).use { c -> var n = 0; while (c.moveToNext()) n++; n }
            if (tableCount < required.size) return "Backup tidak lengkap: tabel inti tidak ditemukan"

            // For legacy (application_id=0) files, additionally demand Room's own
            // identity marker table to distinguish a real Trapezo DB from a foreign SQLite.
            if (!isMarked) {
                val hasRoomMaster = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='room_master_table'", null
                ).use { c -> c.moveToFirst() }
                if (!hasRoomMaster) return "File SQLite ini bukan backup Trapezo POS"
            }

            val integrity = db.rawQuery("PRAGMA quick_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "unknown"
            }
            if (integrity != "ok") return "File backup gagal pemeriksaan integritas SQLite"
            null
        } catch (e: Exception) {
            "Validasi backup gagal: ${e.message}"
        } finally {
            try { db.close() } catch (_: Exception) { }
        }
    }
}
