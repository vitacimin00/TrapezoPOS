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
 * Snapshot safety order:
 *   1. serialize via [lock] (backup/restore never overlap);
 *   2. ensure the Trapezo `application_id` marker is set (idempotent);
 *   3. run a FULL checkpoint and *inspect* its result — a BUSY/incomplete checkpoint
 *      aborts the backup rather than producing a snapshot that misses the marker;
 *   4. close Room cleanly (AppDatabase.closeAndClear) so no SQLite handle can
 *      auto-checkpoint into the main file while bytes are being copied;
 *   5. copy the stable main database;
 *   6. Room reopens lazily through AppGraph on the next access.
 *
 * Room owns `PRAGMA user_version`; we never set it manually. The Trapezo marker
 * is carried by `application_id` only.
 */
class BackupService(private val context: Context) {
    data class BackupResult(val ok: Boolean, val message: String, val fileName: String? = null)

    companion object {
        /** "TRPZ" in big-endian — SQLite application_id marker for Trapezo POS backups. */
        internal const val APP_ID: Int = 0x5452505A
        private const val CURRENT_SCHEMA_VERSION = 5
        private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
        private val REQUIRED_TABLES = listOf(
            "users", "stores", "categories", "products", "product_barcodes",
            "inventory_movements", "customers", "sales", "sale_items", "payments",
            "payment_methods", "shifts", "cash_movements", "refunds", "refund_items",
            "refund_payments", "settings", "audit_logs"
        )
        private val lock = Any()
    }

    fun suggestedName(): String =
        "TrapezoPOS_Backup_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.db"

    /**
     * Returns the checkpoint result as (busy, logPages, checkpointedPages).
     * busy != 0 or logPages != checkpointedPages means the checkpoint did not fully
     * transfer WAL content into the main database file.
     */
    private fun checkpointResult(db: androidx.sqlite.db.SupportSQLiteDatabase): Triple<Int, Int, Int> {
        db.query("PRAGMA wal_checkpoint(FULL)").use { c ->
            return if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2)) else Triple(1, 0, 0)
        }
    }

    suspend fun backupTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            AppDatabase.withMaintenance { current -> try {
                val dbFile = context.getDatabasePath(AppDatabase.NAME)
                if (!dbFile.exists()) return@withMaintenance BackupResult(false, "Database belum tersedia")
                if (dbFile.length() > MAX_BACKUP_BYTES) return@withMaintenance BackupResult(false, "Database terlalu besar untuk dibackup")

                val db = current.openHelper.writableDatabase
                // 1. ensure the Trapezo marker is set (idempotent).
                db.execSQL("PRAGMA application_id = $APP_ID")
                // 2. checkpoint WAL into the main file and inspect the result.
                val (busy, logPages, checkpointed) = checkpointResult(db)
                if (busy != 0 || logPages != checkpointed) {
                    return@withMaintenance BackupResult(false, "Checkpoint tidak selesai (busy=$busy, $checkpointed/$logPages halaman); coba lagi saat tidak ada operasi")
                }
                // 3. close Room so no handle can auto-checkpoint into the file mid-copy.
                AppDatabase.closeAndClear()
                // 4. re-open the raw file read-only and confirm the marker is durable.
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
                // 5. copy the now-stable main database.
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    dbFile.inputStream().use { it.copyTo(out) }
                } ?: return@withMaintenance BackupResult(false, "Tidak bisa menulis file backup")
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
            val previous = File(parent, "${AppDatabase.NAME}.pre_restore")
            val wal = File(parent, "${AppDatabase.NAME}-wal")
            val shm = File(parent, "${AppDatabase.NAME}-shm")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BACKUP_BYTES) throw IllegalStateException("File backup melebihi batas ukuran")
                            out.write(buf, 0, n)
                        }
                    }
                } ?: return@withMaintenance BackupResult(false, "Tidak dapat membaca file yang dipilih")

                val error = validateStaged(staged)
                if (error != null) {
                    staged.delete()
                    return@withMaintenance BackupResult(false, error)
                }

                AppDatabase.closeAndClear()
                if (previous.exists()) previous.delete()
                if (live.exists() && !live.renameTo(previous)) {
                    return@withMaintenance BackupResult(false, "Gagal mengamankan database lama")
                }
                wal.delete()
                shm.delete()
                if (!staged.renameTo(live)) {
                    if (previous.exists()) previous.renameTo(live)
                    return@withMaintenance BackupResult(false, "Gagal menerapkan database restore")
                }
                BackupResult(true, "Restore berhasil. Tutup lalu buka kembali aplikasi untuk memakai data baru.")
            } catch (e: Exception) {
                staged.delete()
                BackupResult(false, "Restore gagal: ${e.message}")
            }
            }
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

            val tableCount = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN " +
                    "(${REQUIRED_TABLES.joinToString(",") { "'$it'" }})", null
            ).use { c -> var n = 0; while (c.moveToNext()) n++; n }
            if (tableCount < REQUIRED_TABLES.size) return "Backup tidak lengkap: tabel inti tidak ditemukan"

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
