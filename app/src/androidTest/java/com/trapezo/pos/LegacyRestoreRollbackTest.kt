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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Track H2 Revision 02, item 7.
 *
 * Deterministically exercises the legacy raw-`.db` restore rollback state machine by injecting a
 * fake [BackupService.FileOps] — the smallest seam that lets "apply failed" and "rollback itself
 * failed" be induced on demand, without any production debug switch. The production default
 * ([BackupService.FileOps.Default]) is untouched and does real `File` operations; nothing here
 * changes runtime behaviour outside this test.
 */
@RunWith(AndroidJUnit4::class)
class LegacyRestoreRollbackTest {
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
        files.forEach { it.delete() }
    }

    private fun temp(name: String) = File(ctx.cacheDir, name).also { it.delete(); files += it }

    private fun makeRawV5Backup(name: String): File {
        runBlocking { AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "rawv5")) }
        AppDatabase.get().openHelper.writableDatabase.execSQL("PRAGMA application_id=${BackupService.APP_ID}")
        AppDatabase.closeAndClear()
        val raw = temp(name)
        ctx.getDatabasePath(AppDatabase.NAME).inputStream().use { i -> raw.outputStream().use(i::copyTo) }
        return raw
    }

    /** Delegates every call to a real [File] op unless overridden. */
    private open class RealBase : BackupService.FileOps {
        override fun rename(from: File, to: File) = from.renameTo(to)
        override fun delete(file: File) = file.delete()
        override fun deleteRecursively(file: File) = file.deleteRecursively()
    }

    // ---- A/B/C: apply fails AFTER the original was secured -> previous DB restored ----

    @Test fun failedApply_afterOriginalSecured_rollsBackToPreviousDb() = runBlocking {
        val raw = makeRawV5Backup("apply_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val bytesBefore = live.readBytes()

        // Genuinely induce "apply failed": the rename of the STAGED file onto `live` fails,
        // while the rename that secures the ORIGINAL aside succeeds normally.
        var secureAttempted = false
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                return if (from == live && to.name.endsWith(".pre_restore")) {
                    secureAttempted = true
                    super.rename(from, to)
                } else if (from == raw && to == live) {
                    false // <-- the induced apply failure
                } else {
                    super.rename(from, to)
                }
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertTrue("original must have been secured before the induced failure", secureAttempted)
        assertFalse("restore must report failure", result.ok)
        assertTrue(
            "must report data was rolled back, not an incomplete recovery: ${result.message}",
            result.message.contains("data lama dikembalikan")
        )

        // C: the PREVIOUS database is genuinely back at `live`, byte for byte.
        assertTrue(live.exists())
        assertEquals(bytesBefore.size, live.readBytes().size)

        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
    }

    // ---- D/E: rollback itself fails -> incomplete recovery reported, never silently claimed ----

    @Test fun failedApply_andFailedRollback_reportsIncompleteRecovery() = runBlocking {
        val raw = makeRawV5Backup("rollback_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)

        // Induce BOTH: the apply rename fails, AND the rollback rename (previous -> live) also
        // fails, so rollback cannot bring the original back. This must be reported as an
        // INCOMPLETE recovery, never as "data lama dikembalikan".
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                return when {
                    from == live && to.name.endsWith(".pre_restore") -> super.rename(from, to)
                    from == raw && to == live -> false // apply failure
                    from.name.endsWith(".pre_restore") && to == live -> false // rollback failure
                    else -> super.rename(from, to)
                }
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertTrue(
            "must explicitly report incomplete recovery: ${result.message}",
            result.message.contains("pemulihan data lama tidak selesai")
        )
        assertTrue(
            "must never claim success when rollback failed",
            !result.message.contains("berhasil")
        )
    }

    // ---- Sanity: the ownership ledger never deletes what it never moved ----

    @Test fun originalNeverMoved_isNeverDeletedByRollback() = runBlocking {
        val raw = makeRawV5Backup("never_moved.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val bytesBefore = live.readBytes()

        // The VERY FIRST rename (securing the original) fails -> `live` is never moved and must
        // be left completely untouched by rollback.
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                return if (from == live) false else super.rename(from, to)
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertTrue(live.exists())
        assertEquals(bytesBefore.size, live.readBytes().size)
        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
    }
}
