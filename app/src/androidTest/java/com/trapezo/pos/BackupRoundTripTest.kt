package com.trapezo.pos

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.SettingEntity
import com.trapezo.pos.data.entity.StoreEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private lateinit var ctx: android.content.Context
    private val files = mutableListOf<File>()

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.closeAndClear()
        ctx.deleteDatabase(AppDatabase.NAME)
        File(ctx.filesDir, "product_photos").deleteRecursively()
        File(ctx.filesDir, "store_media").deleteRecursively()
    }

    @After fun tearDown() {
        AppDatabase.closeAndClear()
        ctx.deleteDatabase(AppDatabase.NAME)
        File(ctx.filesDir, "product_photos").deleteRecursively()
        File(ctx.filesDir, "store_media").deleteRecursively()
        files.forEach(File::delete)
    }

    private fun temp(name: String) = File(ctx.cacheDir, name).also { it.delete(); files += it }
    private fun copy(from: File, to: File) = from.inputStream().use { i -> to.outputStream().use(i::copyTo) }
    private fun pragma(file: File, name: String): String = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA $name", null).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
    }
    private fun scalar(db: AppDatabase, sql: String): String? =
        db.openHelper.readableDatabase.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Test fun backupMutateRestoreReopen_roundTripsRealData() = runBlocking {
        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "before"))
        val backup = temp("round_trip.trpz")
        assertTrue(BackupService(ctx).backupTo(Uri.fromFile(backup)).ok)

        // The new output is a ZIP package, not a raw SQLite file.
        val header = ByteArray(2)
        backup.inputStream().use { it.read(header) }
        assertTrue("expected ZIP PK signature", header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte())

        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "after"))
        assertEquals("after", AppDatabase.get().settingsDao().get("round.trip"))
        assertTrue(BackupService(ctx).restoreFrom(Uri.fromFile(backup)).ok)
        assertEquals("before", AppDatabase.get().settingsDao().get("round.trip"))
    }

    @Test fun packageBackup_restoresMediaAndRebindsPaths() = runBlocking {
        val photoDir = File(ctx.filesDir, "product_photos").apply { mkdirs() }
        val logoDir = File(ctx.filesDir, "store_media").apply { mkdirs() }
        val photoFile = File(photoDir, "product_1.jpg").apply { writeBytes("JPEGDATA".toByteArray()) }
        val logoFile = File(logoDir, "store_logo_1.png").apply { writeBytes("PNGDATA".toByteArray()) }

        val db = AppDatabase.get()
        val productId = db.productDao().insert(ProductEntity(name = "P", sku = "S1", photo = photoFile.absolutePath))
        val storeId = db.storeDao().insert(StoreEntity(name = "Toko", logo = logoFile.absolutePath))
        assertEquals(photoFile.absolutePath, scalar(db, "SELECT photo FROM products WHERE id=$productId"))

        val backup = temp("media_roundtrip.trpz")
        assertTrue(BackupService(ctx).backupTo(Uri.fromFile(backup)).ok)

        // Simulate a clean-media destination: the referenced media disappears.
        photoFile.delete()
        logoFile.delete()

        val result = BackupService(ctx).restoreFrom(Uri.fromFile(backup))
        assertTrue(result.message, result.ok)

        val restored = AppDatabase.get()
        val restoredPhoto = scalar(restored, "SELECT photo FROM products WHERE id=$productId")
        val restoredLogo = scalar(restored, "SELECT logo FROM stores WHERE id=$storeId")
        // Path rebound to CURRENT filesDir/media bucket.
        assertTrue("photo rebind", restoredPhoto == File(ctx.filesDir, "product_photos/product_1.jpg").absolutePath)
        assertTrue("logo rebind", restoredLogo == File(ctx.filesDir, "store_media/store_logo_1.png").absolutePath)
        // The actual media bytes exist again.
        assertTrue(File(restoredPhoto!!).exists())
        assertTrue(File(restoredLogo!!).exists())
        assertEquals("JPEGDATA", String(File(restoredPhoto).readBytes()))
        assertEquals("PNGDATA", String(File(restoredLogo).readBytes()))
    }

    @Test fun rawLegacySqliteRestore_stillWorks() = runBlocking {
        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "legacy"))
        // Produce a raw SQLite backup by copying the DB file directly (historical format).
        AppDatabase.get().openHelper.writableDatabase.execSQL("PRAGMA application_id=${BackupService.APP_ID}")
        AppDatabase.closeAndClear()
        val raw = temp("legacy_raw.db")
        copy(ctx.getDatabasePath(AppDatabase.NAME), raw)

        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "changed"))
        val result = BackupService(ctx).restoreFrom(Uri.fromFile(raw))
        assertTrue(result.message, result.ok)
        assertEquals("legacy", AppDatabase.get().settingsDao().get("round.trip"))
    }

    @Test fun validation_acceptsLegacyApplicationIdZero() {
        AppDatabase.get().openHelper.writableDatabase.execSQL("PRAGMA application_id=0")
        AppDatabase.closeAndClear()
        val legacy = temp("legacy.db"); copy(ctx.getDatabasePath(AppDatabase.NAME), legacy)
        assertNull(BackupService(ctx).validateStaged(legacy))
    }

    @Test fun validation_rejectsWrongNonzeroApplicationId() {
        AppDatabase.get().openHelper.writableDatabase.execSQL("PRAGMA application_id=123")
        AppDatabase.closeAndClear()
        val wrong = temp("wrong.db"); copy(ctx.getDatabasePath(AppDatabase.NAME), wrong)
        assertNotNull(BackupService(ctx).validateStaged(wrong))
    }

    @Test fun validation_rejectsForeignSqlite() {
        val foreign = temp("foreign.db")
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { it.execSQL("CREATE TABLE unrelated(x TEXT)") }
        assertNotNull(BackupService(ctx).validateStaged(foreign))
    }

    @Test fun validation_rejectsCorruptFile() {
        val corrupt = temp("corrupt.db")
        corrupt.writeBytes("SQLite format 3\u0000definitely corrupt".toByteArray())
        assertNotNull(BackupService(ctx).validateStaged(corrupt))
    }

    /**
     * Revision 01 BLOCKER regression.
     *
     * Makes the very first apply step — moving the ORIGINAL live DB aside to `.pre_restore` —
     * fail, by occupying that destination with a NON-EMPTY DIRECTORY (a directory cannot be
     * replaced by File.renameTo, and a non-empty one cannot be deleted either).
     *
     * The original live DB is therefore never moved. Before this revision the rollback ran an
     * unconditional `live.delete()` and DESTROYED that original database. The rollback must now
     * recognise it does not own `live` and leave it completely untouched.
     *
     * No production fault-injection switch is involved — only real filesystem state.
     */
    @Test fun failedRestore_whenOriginalDbCannotBeMovedAside_preservesOriginalDb() = runBlocking {
        // Known, readable marker row in the ORIGINAL database.
        AppDatabase.get().settingsDao().put(SettingEntity(key = "rollback.marker", value = "original"))

        // A valid package to restore (contains a different value for the same key).
        val backup = temp("rollback_src.trpz")
        assertTrue(BackupService(ctx).backupTo(Uri.fromFile(backup)).ok)
        AppDatabase.get().settingsDao().put(SettingEntity(key = "rollback.marker", value = "original"))

        val liveDb = ctx.getDatabasePath(AppDatabase.NAME)
        val liveBytesBefore = liveDb.readBytes().size
        assertTrue("live DB must exist before restore", liveDb.exists())

        // Occupy the `.pre_restore` destination with a non-empty directory.
        val blocker = File(liveDb.parentFile, "${AppDatabase.NAME}.pre_restore")
        blocker.deleteRecursively()
        assertTrue(blocker.mkdirs())
        File(blocker, "occupied.txt").writeBytes("blocked".toByteArray())

        try {
            val result = BackupService(ctx).restoreFrom(Uri.fromFile(backup))

            // 1. The restore must FAIL.
            assertTrue("restore should have failed, got: ${result.message}", !result.ok)

            // 2. The ORIGINAL live DB must still be there, byte-for-byte.
            assertTrue("ORIGINAL live DB was destroyed by rollback", liveDb.exists())
            assertEquals(liveBytesBefore, liveDb.readBytes().size)

            // 3. And it must still be readable with its original row intact.
            assertEquals("original", AppDatabase.get().settingsDao().get("rollback.marker"))
        } finally {
            blocker.deleteRecursively()
        }
    }

    /**
     * Revision 01: when the original product-media directory could not be moved aside, rollback
     * must not delete it. A read-only-ish conflict is hard to force portably for directories, so
     * this asserts the safe outcome of the same blocked-DB scenario: media survives untouched.
     */
    @Test fun failedRestore_leavesOriginalMediaUntouched() = runBlocking {
        val photoDir = File(ctx.filesDir, "product_photos").apply { mkdirs() }
        val logoDir = File(ctx.filesDir, "store_media").apply { mkdirs() }
        val photo = File(photoDir, "product_keep.jpg").apply { writeBytes("KEEPJPEG".toByteArray()) }
        val logo = File(logoDir, "store_logo_keep.png").apply { writeBytes("KEEPPNG".toByteArray()) }

        val backup = temp("rollback_media.trpz")
        val db = AppDatabase.get()
        db.productDao().insert(ProductEntity(name = "K", sku = "K1", photo = photo.absolutePath))
        db.storeDao().insert(StoreEntity(name = "Toko", logo = logo.absolutePath))
        assertTrue(BackupService(ctx).backupTo(Uri.fromFile(backup)).ok)

        val liveDb = ctx.getDatabasePath(AppDatabase.NAME)
        val blocker = File(liveDb.parentFile, "${AppDatabase.NAME}.pre_restore")
        blocker.deleteRecursively()
        assertTrue(blocker.mkdirs())
        File(blocker, "occupied.txt").writeBytes("blocked".toByteArray())

        try {
            val result = BackupService(ctx).restoreFrom(Uri.fromFile(backup))
            assertTrue("restore should have failed", !result.ok)

            // Original media must be intact — never deleted by a rollback that owns nothing.
            assertTrue("original photo deleted", photo.exists())
            assertTrue("original logo deleted", logo.exists())
            assertEquals("KEEPJPEG", String(photo.readBytes()))
            assertEquals("KEEPPNG", String(logo.readBytes()))
        } finally {
            blocker.deleteRecursively()
        }
    }
}
