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
 * Local SQLite backup/restore through Storage Access Framework streams.
 *
 * Hardening guarantees:
 *  - a FULL WAL checkpoint runs before the raw `.db` is copied, so the backup
 *    never depends on sidecar `-wal`/`-shm` files a SAF stream cannot capture;
 *  - the live database is stamped with a `PRAGMA application_id` marker so a
 *    restore can positively identify a Trapezo POS backup;
 *  - restore validates size, SQLite header, application marker, schema version
 *    and required core tables before the current database is ever touched;
 *  - restore is staged, the old database is preserved as `.pre_restore`, and the
 *    old file is only displaced after the staged file is verified.
 */
class BackupService(private val context: Context) {
    data class BackupResult(val ok: Boolean, val message: String, val fileName: String? = null)

    companion object {
        /** "TRPZ" in big-endian — SQLite application_id marker for Trapezo POS backups. */
        private const val APP_ID: Int = 0x5452505A
        private const val CURRENT_SCHEMA_VERSION = 5
        private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
        private val REQUIRED_TABLES = listOf(
            "users", "stores", "categories", "products", "product_barcodes",
            "inventory_movements", "customers", "sales", "sale_items", "payments",
            "payment_methods", "shifts", "cash_movements", "refunds", "refund_items",
            "refund_payments", "settings", "audit_logs"
        )
    }

    fun suggestedName(): String =
        "TrapezoPOS_Backup_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.db"

    private fun stampApplicationId() {
        val db = AppDatabase.get().openHelper.writableDatabase
        db.execSQL("PRAGMA application_id = $APP_ID")
        db.execSQL("PRAGMA user_version = $CURRENT_SCHEMA_VERSION")
    }

    suspend fun backupTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            stampApplicationId()
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            if (!dbFile.exists()) return@withContext BackupResult(false, "Database belum tersedia")
            if (dbFile.length() > MAX_BACKUP_BYTES) return@withContext BackupResult(false, "Database terlalu besar untuk dibackup")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                dbFile.inputStream().use { it.copyTo(out) }
            } ?: return@withContext BackupResult(false, "Tidak bisa menulis file backup")
            BackupResult(true, "Backup berhasil dibuat", suggestedName())
        } catch (e: Exception) {
            BackupResult(false, "Backup gagal: ${e.message}")
        }
    }

    suspend fun restoreFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val live = context.getDatabasePath(AppDatabase.NAME)
        val parent = live.parentFile ?: return@withContext BackupResult(false, "Folder database tidak tersedia")
        val staged = File(parent, "${AppDatabase.NAME}.restore_staged")
        val previous = File(parent, "${AppDatabase.NAME}.pre_restore")
        val wal = File(parent, "${AppDatabase.NAME}-wal")
        val shm = File(parent, "${AppDatabase.NAME}-shm")
        try {
            // 1. stream the incoming file with a hard size cap, never materialising a huge blob.
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BACKUP_BYTES) {
                            throw IllegalStateException("File backup melebihi batas ukuran")
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: return@withContext BackupResult(false, "Tidak dapat membaca file yang dipilih")

            // 2. validate: SQLite header, metadata, schema version and required tables.
            val error = validateStaged(staged)
            if (error != null) {
                staged.delete()
                return@withContext BackupResult(false, error)
            }

            // 3. close the live Room handle so no Windows/SQLite file lock survives.
            AppDatabase.closeAndClear()
            if (previous.exists()) previous.delete()
            if (live.exists() && !live.renameTo(previous)) {
                return@withContext BackupResult(false, "Gagal mengamankan database lama")
            }
            wal.delete()
            shm.delete()
            if (!staged.renameTo(live)) {
                // Atomic recovery attempt: old DB remains available if replacement failed.
                if (previous.exists()) previous.renameTo(live)
                return@withContext BackupResult(false, "Gagal menerapkan database restore")
            }
            BackupResult(true, "Restore berhasil. Tutup lalu buka kembali aplikasi untuk memakai data baru.")
        } catch (e: Exception) {
            staged.delete()
            BackupResult(false, "Restore gagal: ${e.message}")
        }
    }

    /** Returns a user-facing error message, or null when the staged backup is valid. */
    private fun validateStaged(staged: File): String? {
        if (!staged.exists() || staged.length() == 0L) return "File backup kosong"
        val header = ByteArray(16)
        staged.inputStream().use { input -> input.read(header) }
        val magic = header.copyOfRange(0, 16).toString(Charsets.US_ASCII)
        if (!magic.startsWith("SQLite format 3")) return "File bukan database SQLite yang valid"

        // Open read-only to interrogate metadata + table presence without mutating.
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
            if (appId != APP_ID) return "File bukan backup Trapezo POS (penanda aplikasi tidak cocok)"
            if (userVersion < 1 || userVersion > CURRENT_SCHEMA_VERSION) {
                return "Versi skema backup ($userVersion) tidak kompatibel dengan aplikasi ($CURRENT_SCHEMA_VERSION)"
            }
            val tableCount = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN " +
                    "(${REQUIRED_TABLES.joinToString(",") { "'$it'" }})", null
            ).use { c -> var n = 0; while (c.moveToNext()) n++; n }
            if (tableCount < REQUIRED_TABLES.size) return "Backup tidak lengkap: tabel inti tidak ditemukan"
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
