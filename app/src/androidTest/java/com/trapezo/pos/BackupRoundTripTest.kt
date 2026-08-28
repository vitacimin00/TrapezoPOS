package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the backup snapshot contains the Trapezo marker before copying — the exact
 * checkpoint-order bug this revision fixes. Runs on a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    private lateinit var ctx: android.content.Context

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.closeAndClear()
    }

    @After fun tearDown() { AppDatabase.closeAndClear() }

    private fun copyBytes(from: File, to: File) = from.inputStream().use { i -> to.outputStream().use { o -> i.copyTo(o) } }

    @Test fun backupFile_carriesMarkerAndPassesIntegrity() {
        runBlocking {
        // 1. create real Trapezo DB with representative data.
        val db = AppDatabase.get()
        db.userDao().insert(TestDb.admin(active = true))
        db.productDao().insert(com.trapezo.pos.data.entity.ProductEntity(name = "Kopi", sku = "K1", sellPrice = 1000))
        db.close()

        // 2. produce a backup into a temp file.
        val backupFile = File(ctx.cacheDir, "backup_test.db")
        backupFile.delete()
        val service = BackupService(ctx)
        val uri = android.net.Uri.fromFile(backupFile)
        val result = service.backupTo(uri)
        assertTrue(result.ok)
        assertTrue(backupFile.exists())

        // 3. the copied raw file must ALREADY carry the marker (this is the bug).
        val probe = android.database.sqlite.SQLiteDatabase.openDatabase(backupFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        val appId = probe.rawQuery("PRAGMA application_id", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val userVersion = probe.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val integrity = probe.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        probe.close()

        assertEquals(BackupService.APP_ID, appId)       // marker embedded in raw file
        assertEquals(5, userVersion)                    // Room schema version untouched
        assertEquals("ok", integrity)                   // not corrupt
        backupFile.delete()
    }
    }

    @Test fun restoreValidation_acceptsMarkedBackup() {
        val good = File(ctx.cacheDir, "restore_marked.db")
        good.delete()
        val dbFile = ctx.getDatabasePath(AppDatabase.NAME)
        // ensure live DB has marker
        AppDatabase.get().openHelper.writableDatabase.execSQL("PRAGMA application_id = ${BackupService.APP_ID}")
        AppDatabase.closeAndClear()
        copyBytes(dbFile, good)
        assertNull(BackupService(ctx).validateStaged(good))
        good.delete()
    }

    @Test fun restoreValidation_rejectsForeignSqlite() {
        val foreign = File(ctx.cacheDir, "restore_foreign.db")
        foreign.delete()
        // a plain SQLite file with no Trapezo schema/marker
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(foreign, null).close()
        val error = BackupService(ctx).validateStaged(foreign)
        assertTrue(error != null)
        foreign.delete()
    }

    @Test fun restoreValidation_rejectsTruncatedFile() {
        val bad = File(ctx.cacheDir, "restore_bad.db")
        bad.writeBytes(byteArrayOf(0x53, 0x51, 0x4c)) // "SQL" — not a real db
        val error = BackupService(ctx).validateStaged(bad)
        assertTrue(error != null)
        bad.delete()
    }
}
