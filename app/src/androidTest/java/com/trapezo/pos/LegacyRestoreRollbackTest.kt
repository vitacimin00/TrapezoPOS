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
import org.junit.Assert.assertArrayEquals
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
        assertArrayEquals("previous DB not restored byte-for-byte", bytesBefore, live.readBytes())

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
        assertArrayEquals("original DB not preserved byte-for-byte", bytesBefore, live.readBytes())
        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
    }

    // ---- SQLite sidecar securing fails -> fail CLOSED (Revision 04 semantics) ----
    //
    // Revision 03 DELETED the old sidecars before commit; Revision 04 MOVES them to temp
    // locations so rollback can restore them. These two tests therefore induce a failure of the
    // *securing move*, which is the Revision 04 equivalent of "the old sidecar cannot be cleared
    // from the canonical live name".

    /**
     * An OLD `-wal` that cannot be moved out of the canonical live sidecar name must fail CLOSED:
     * the staged database is never applied, and the original database plus its WAL are untouched.
     */
    @Test fun walThatCannotBeSecured_failsClosedAndKeepsOriginal() = runBlocking {
        val raw = makeRawV5Backup("wal_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val bytesBefore = live.readBytes()

        // An OLD wal sits beside the live DB.
        val wal = File(live.parentFile, "${AppDatabase.NAME}-wal")
        val walBytes = ByteArray(64) { 0x7f }
        wal.writeBytes(walBytes)
        assertTrue("precondition: stale wal exists", wal.exists())

        var applyAttempted = false
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                if (from == raw && to == live) applyAttempted = true
                // securing the WAL out of the live name fails
                if (from == wal && to.name.endsWith(".pre_restore-wal")) return false
                return super.rename(from, to)
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse("restore must fail when the old WAL cannot be secured", result.ok)
        assertFalse("staged DB must NEVER be applied after sidecar securing failed", applyAttempted)
        assertTrue(
            "message must not claim success: ${result.message}",
            !result.message.contains("berhasil dipulihkan")
        )
        // The ORIGINAL database is back, byte-for-byte, and still readable.
        assertTrue(live.exists())
        assertArrayEquals("original DB not preserved byte-for-byte", bytesBefore, live.readBytes())
        // The WAL was never moved, so the original is still exactly in place.
        assertTrue("original WAL must remain in place", wal.exists())
        assertArrayEquals("original WAL altered", walBytes, wal.readBytes())
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))

        AppDatabase.closeAndClear()
        wal.delete()
        Unit
    }

    /** Same invariant for the `-shm` sidecar. */
    @Test fun shmThatCannotBeSecured_failsClosedAndKeepsOriginal() = runBlocking {
        val raw = makeRawV5Backup("shm_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val bytesBefore = live.readBytes()

        val shm = File(live.parentFile, "${AppDatabase.NAME}-shm")
        val shmBytes = ByteArray(32) { 0x5a }
        shm.writeBytes(shmBytes)
        assertTrue("precondition: stale shm exists", shm.exists())

        var applyAttempted = false
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                if (from == raw && to == live) applyAttempted = true
                if (from == shm && to.name.endsWith(".pre_restore-shm")) return false
                return super.rename(from, to)
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertFalse("staged DB must never be applied", applyAttempted)
        assertTrue(live.exists())
        assertArrayEquals("original DB not preserved byte-for-byte", bytesBefore, live.readBytes())
        assertTrue("original SHM must remain in place", shm.exists())
        assertArrayEquals("original SHM altered", shmBytes, shm.readBytes())
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))

        AppDatabase.closeAndClear()
        shm.delete()
        Unit
    }

    /** With no sidecars present there is nothing to secure: restore proceeds unchanged (E). */
    @Test fun absentSidecars_doNotBlockRestore() = runBlocking {
        val raw = makeRawV5Backup("no_sidecar.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        File(live.parentFile, "${AppDatabase.NAME}-wal").delete()
        File(live.parentFile, "${AppDatabase.NAME}-shm").delete()

        // Absent sidecars must not be treated as a failure: nothing to secure, nothing to roll back.
        val result = BackupService(ctx, RealBase()).restoreLegacy(raw, live)

        assertTrue("restore must succeed with no sidecars present: ${result.message}", result.ok)
        assertEquals("rawv5", AppDatabase.get().settingsDao().get("legacy.probe"))
    }

    // ---- Revision 03: POST-COMMIT cleanup failure must NOT roll back a valid restore ----

    /**
     * Once the new database is applied and live it is authoritative. If removing the temporary
     * `.pre_restore` copy afterwards fails, the restore stays successful and only a warning is
     * added — rolling back committed data over a leftover temp file would be far worse.
     */
    @Test fun postCommitCleanupFailure_keepsNewDatabaseAndWarns() = runBlocking {
        val raw = makeRawV5Backup("cleanup_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)

        // Only the POST-COMMIT delete of `.pre_restore` fails; everything before it succeeds.
        val ops = object : RealBase() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(".pre_restore")) false else super.delete(file)
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertTrue("restore must remain successful: ${result.message}", result.ok)
        assertTrue(
            "must warn about temp cleanup: ${result.message}",
            result.message.contains("cadangan sementara tidak dapat dibersihkan")
        )
        assertFalse(
            "must not leak filesystem paths: ${result.message}",
            result.message.contains(".pre_restore") ||
                result.message.contains("/data/") ||
                result.message.contains(AppDatabase.NAME)
        )
        // The NEW database is live and authoritative — not rolled back.
        assertEquals("rawv5", AppDatabase.get().settingsDao().get("legacy.probe"))

        AppDatabase.closeAndClear()
        File(live.parentFile, "${AppDatabase.NAME}.pre_restore").delete()
        Unit
    }

    // ---- Revision 04: sidecars are SECURED and roll back with the database ----

    private fun dbDir() = ctx.getDatabasePath(AppDatabase.NAME).parentFile!!
    private fun walFile() = File(dbDir(), "${AppDatabase.NAME}-wal")
    private fun shmFile() = File(dbDir(), "${AppDatabase.NAME}-shm")
    private fun preWal() = File(dbDir(), "${AppDatabase.NAME}.pre_restore-wal")
    private fun preShm() = File(dbDir(), "${AppDatabase.NAME}.pre_restore-shm")

    /** A: WAL secured, later apply fails -> main DB AND WAL both come back byte-for-byte. */
    @Test fun failedApply_restoresOriginalDatabaseAndWal_byteForByte() = runBlocking {
        val raw = makeRawV5Backup("wal_rollback.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val dbBefore = live.readBytes()

        val wal = walFile()
        val walBytes = ByteArray(128) { (it * 7 % 251).toByte() }
        wal.writeBytes(walBytes)

        // Apply of the staged DB fails AFTER main + WAL were secured.
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean =
                if (from == raw && to == live) false else super.rename(from, to)
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertTrue(
            "must report the old data came back: ${result.message}",
            result.message.contains("data lama dikembalikan")
        )
        // BYTE-FOR-BYTE, asserted before Room reopens the file.
        assertArrayEquals("original DB not restored byte-for-byte", dbBefore, live.readBytes())
        assertTrue("original WAL not restored", wal.exists())
        assertArrayEquals("WAL not restored byte-for-byte", walBytes, wal.readBytes())
        assertFalse("previous-WAL temp leaked", preWal().exists())

        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
        AppDatabase.closeAndClear()
        wal.delete()
        Unit
    }

    /** B: WAL + SHM both secured, later apply fails -> all three restored byte-for-byte. */
    @Test fun failedApply_restoresDatabaseWalAndShm_byteForByte() = runBlocking {
        val raw = makeRawV5Backup("wal_shm_rollback.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val dbBefore = live.readBytes()

        val wal = walFile()
        val shm = shmFile()
        val walBytes = ByteArray(96) { (it * 3 % 253).toByte() }
        val shmBytes = ByteArray(48) { (it * 11 % 249).toByte() }
        wal.writeBytes(walBytes)
        shm.writeBytes(shmBytes)

        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean =
                if (from == raw && to == live) false else super.rename(from, to)
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertArrayEquals(dbBefore, live.readBytes())
        assertTrue(wal.exists())
        assertArrayEquals("WAL not restored byte-for-byte", walBytes, wal.readBytes())
        assertTrue(shm.exists())
        assertArrayEquals("SHM not restored byte-for-byte", shmBytes, shm.readBytes())
        assertFalse(preWal().exists())
        assertFalse(preShm().exists())

        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
        AppDatabase.closeAndClear()
        wal.delete(); shm.delete()
        Unit
    }

    /** C: WAL secured but securing the SHM fails -> new DB never applied, earlier state restored. */
    @Test fun shmSecureFailure_neverAppliesNewDb_andRestoresEarlierResources() = runBlocking {
        val raw = makeRawV5Backup("shm_secure_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val dbBefore = live.readBytes()

        val wal = walFile()
        val shm = shmFile()
        val walBytes = ByteArray(64) { 0x21 }
        val shmBytes = ByteArray(24) { 0x42 }
        wal.writeBytes(walBytes)
        shm.writeBytes(shmBytes)

        var applyAttempted = false
        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean {
                if (from == raw && to == live) applyAttempted = true
                // Securing the SHM fails; securing the WAL and the main DB succeed.
                if (from == shm && to.name.endsWith(".pre_restore-shm")) return false
                return super.rename(from, to)
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertFalse("new DB must NEVER be applied when securing a sidecar failed", applyAttempted)
        assertArrayEquals("original DB not restored byte-for-byte", dbBefore, live.readBytes())
        assertTrue("original WAL must be restored", wal.exists())
        assertArrayEquals(walBytes, wal.readBytes())
        // The SHM was never moved, so it is still the original in place, untouched.
        assertTrue("original SHM must remain in place", shm.exists())
        assertArrayEquals(shmBytes, shm.readBytes())

        AppDatabase.get()
        assertEquals("original", AppDatabase.get().settingsDao().get("legacy.probe"))
        AppDatabase.closeAndClear()
        wal.delete(); shm.delete()
        Unit
    }

    /** D: rollback of the secured WAL itself fails -> incomplete recovery reported honestly. */
    @Test fun walRollbackFailure_reportsIncompleteRecovery() = runBlocking {
        val raw = makeRawV5Backup("wal_rollback_fail.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val wal = walFile()
        wal.writeBytes(ByteArray(80) { 0x33 })

        val ops = object : RealBase() {
            override fun rename(from: File, to: File): Boolean = when {
                from == raw && to == live -> false                                  // apply fails
                from.name.endsWith(".pre_restore-wal") && to == wal -> false        // WAL rollback fails
                else -> super.rename(from, to)
            }
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertFalse(result.ok)
        assertTrue(
            "must explicitly report incomplete recovery: ${result.message}",
            result.message.contains("pemulihan data lama tidak selesai")
        )
        assertFalse(
            "must NOT claim the old data was fully returned",
            result.message.contains("data lama dikembalikan")
        )
        assertTrue(
            "must name the WAL as unrecovered: ${result.message}",
            result.message.contains("WAL")
        )

        AppDatabase.closeAndClear()
        preWal().delete(); wal.delete()
        Unit
    }

    /** F: a successful restore leaves NO old sidecar at the canonical live names. */
    @Test fun successfulRestore_leavesNoStaleSidecarsAtLiveNames() = runBlocking {
        val raw = makeRawV5Backup("sidecar_success.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val wal = walFile()
        val shm = shmFile()
        wal.writeBytes(ByteArray(72) { 0x6b })
        shm.writeBytes(ByteArray(36) { 0x1c })

        val result = BackupService(ctx, RealBase()).restoreLegacy(raw, live)

        assertTrue("restore must succeed: ${result.message}", result.ok)
        assertFalse("stale WAL left at the live sidecar name", wal.exists())
        assertFalse("stale SHM left at the live sidecar name", shm.exists())
        assertFalse("previous-WAL temp not cleaned", preWal().exists())
        assertFalse("previous-SHM temp not cleaned", preShm().exists())
        assertFalse(
            "clean restore must not emit a cleanup warning: ${result.message}",
            result.message.contains("cadangan sementara tidak dapat dibersihkan")
        )
        assertEquals("rawv5", AppDatabase.get().settingsDao().get("legacy.probe"))
        AppDatabase.closeAndClear()
        Unit
    }

    /** Post-commit failure to clean a secured WAL temp warns but keeps the new DB. */
    @Test fun postCommitWalTempCleanupFailure_keepsNewDatabaseAndWarns() = runBlocking {
        val raw = makeRawV5Backup("wal_temp_cleanup.db")
        AppDatabase.get().settingsDao().put(SettingEntity(key = "legacy.probe", value = "original"))
        AppDatabase.closeAndClear()

        val live = ctx.getDatabasePath(AppDatabase.NAME)
        val wal = walFile()
        wal.writeBytes(ByteArray(56) { 0x09 })

        // Only the POST-COMMIT delete of the secured WAL temp fails.
        val ops = object : RealBase() {
            override fun delete(file: File): Boolean =
                if (file.name.endsWith(".pre_restore-wal")) false else super.delete(file)
        }

        val result = BackupService(ctx, ops).restoreLegacy(raw, live)

        assertTrue("restore must remain successful: ${result.message}", result.ok)
        assertTrue(
            "must warn about temp cleanup: ${result.message}",
            result.message.contains("cadangan sementara tidak dapat dibersihkan")
        )
        assertEquals("rawv5", AppDatabase.get().settingsDao().get("legacy.probe"))
        AppDatabase.closeAndClear()
        preWal().delete()
        Unit
    }
}
