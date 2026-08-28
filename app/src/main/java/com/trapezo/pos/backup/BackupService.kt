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

/** Local SQLite backup/restore through Storage Access Framework streams. */
class BackupService(private val context: Context) {
    data class BackupResult(val ok: Boolean, val message: String, val fileName: String? = null)

    fun suggestedName(): String =
        "TrapezoPOS_Backup_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.db"

    /**
     * Forces a WAL checkpoint first so the copied `.db` includes committed rows
     * rather than relying on sidecar files that a SAF backup does not include.
     */
    suspend fun backupTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            AppDatabase.get().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            if (!dbFile.exists()) return@withContext BackupResult(false, "Database belum tersedia")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                dbFile.inputStream().use { it.copyTo(out) }
            } ?: return@withContext BackupResult(false, "Tidak bisa menulis file backup")
            BackupResult(true, "Backup berhasil dibuat", suggestedName())
        } catch (e: Exception) {
            BackupResult(false, "Backup gagal: ${e.message}")
        }
    }

    /**
     * Stages/validates the selected SQLite file before touching the live DB.
     * The old database becomes `.pre_restore`; it is never deleted before the
     * staged database has been fully copied and its header validated.
     *
     * Callers must prompt the user to restart/open the app after success.
     */
    suspend fun restoreFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val live = context.getDatabasePath(AppDatabase.NAME)
        val parent = live.parentFile ?: return@withContext BackupResult(false, "Folder database tidak tersedia")
        val staged = File(parent, "${AppDatabase.NAME}.restore_staged")
        val previous = File(parent, "${AppDatabase.NAME}.pre_restore")
        val wal = File(parent, "${AppDatabase.NAME}-wal")
        val shm = File(parent, "${AppDatabase.NAME}-shm")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext BackupResult(false, "Tidak dapat membaca file yang dipilih")

            val header = staged.inputStream().use { input ->
                ByteArray(16).also { input.read(it) }
            }.toString(Charsets.US_ASCII)
            if (!header.startsWith("SQLite format 3")) {
                staged.delete()
                return@withContext BackupResult(false, "File bukan backup database SQLite Trapezo POS")
            }

            // No open Room/SQLite handle may keep Windows file locks alive.
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
}
