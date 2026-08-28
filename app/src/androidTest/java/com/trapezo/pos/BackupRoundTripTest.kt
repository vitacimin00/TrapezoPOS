package com.trapezo.pos

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.SettingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
    }

    @After fun tearDown() {
        AppDatabase.closeAndClear()
        ctx.deleteDatabase(AppDatabase.NAME)
        files.forEach(File::delete)
    }

    private fun temp(name: String) = File(ctx.cacheDir, name).also { it.delete(); files += it }
    private fun copy(from: File, to: File) = from.inputStream().use { i -> to.outputStream().use(i::copyTo) }
    private fun pragma(file: File, name: String): String = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA $name", null).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
    }

    @Test fun backupMutateRestoreReopen_roundTripsRealDataAndMetadata() = runBlocking {
        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "before"))
        val backup = temp("round_trip.db")
        assertTrue(BackupService(ctx).backupTo(Uri.fromFile(backup)).ok)
        assertEquals(BackupService.APP_ID.toString(), pragma(backup, "application_id"))
        assertEquals("5", pragma(backup, "user_version"))
        assertEquals("ok", pragma(backup, "quick_check"))

        AppDatabase.get().settingsDao().put(SettingEntity(key = "round.trip", value = "after"))
        assertEquals("after", AppDatabase.get().settingsDao().get("round.trip"))
        assertTrue(BackupService(ctx).restoreFrom(Uri.fromFile(backup)).ok)
        assertEquals("before", AppDatabase.get().settingsDao().get("round.trip"))
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
}
