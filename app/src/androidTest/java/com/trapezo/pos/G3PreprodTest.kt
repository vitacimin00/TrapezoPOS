package com.trapezo.pos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.ui.AppViewModel
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.repository.ShiftRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Track G3 hardening verifications: migration-aware validation, cash reason, atomic settings. */
@RunWith(AndroidJUnit4::class)
class G3PreprodTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    // ---- historical fixture (same reverse-delta philosophy as MigrationTest) ----
    private fun copyTable(db: SQLiteDatabase, table: String, create: String, columns: String, indexes: List<String>) {
        val copy = "${table}_g3_copy"
        db.execSQL("DROP TABLE IF EXISTS `$copy`")
        db.execSQL(create)
        db.execSQL("INSERT INTO `$copy` ($columns) SELECT $columns FROM `$table`")
        db.execSQL("DROP TABLE `$table`")
        db.execSQL("ALTER TABLE `$copy` RENAME TO `$table`")
        indexes.forEach(db::execSQL)
    }

    private fun reverseV5(db: SQLiteDatabase) = copyTable(db, "shifts",
        "CREATE TABLE `shifts_g3_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `userNameSnapshot` TEXT NOT NULL, `openingCash` INTEGER NOT NULL, `totalCashSales` INTEGER NOT NULL, `totalNonCashSales` INTEGER NOT NULL, `cashIn` INTEGER NOT NULL, `cashOut` INTEGER NOT NULL, `expectedCash` INTEGER NOT NULL, `actualCash` INTEGER NOT NULL, `difference` INTEGER NOT NULL, `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `status` TEXT NOT NULL)",
        "`id`,`userId`,`userNameSnapshot`,`openingCash`,`totalCashSales`,`totalNonCashSales`,`cashIn`,`cashOut`,`expectedCash`,`actualCash`,`difference`,`openedAt`,`closedAt`,`status`",
        listOf("CREATE INDEX `index_shifts_userId` ON `shifts` (`userId`)", "CREATE INDEX `index_shifts_status` ON `shifts` (`status`)", "CREATE INDEX `index_shifts_openedAt` ON `shifts` (`openedAt`)"))

    private fun reverseV4(db: SQLiteDatabase) = copyTable(db, "users",
        "CREATE TABLE `users_g3_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `username` TEXT NOT NULL, `passwordHash` TEXT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "`id`,`username`,`passwordHash`,`name`,`role`,`isActive`,`createdAt`,`updatedAt`",
        listOf("CREATE UNIQUE INDEX `index_users_username` ON `users` (`username`)"))

    private fun reverseV3(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE `refund_payments`")
        copyTable(db, "refunds",
            "CREATE TABLE `refunds_g3_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `saleId` INTEGER NOT NULL, `userId` INTEGER NOT NULL, `total` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "`id`,`saleId`,`userId`,`total`,`reason`,`createdAt`",
            listOf("CREATE INDEX `index_refunds_saleId` ON `refunds` (`saleId`)"))
        copyTable(db, "sale_items",
            "CREATE TABLE `sale_items_g3_copy` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `saleId` INTEGER NOT NULL, `productId` INTEGER, `productNameSnapshot` TEXT NOT NULL, `barcodeSnapshot` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `unitPrice` INTEGER NOT NULL, `discount` INTEGER NOT NULL, `subtotal` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "`id`,`saleId`,`productId`,`productNameSnapshot`,`barcodeSnapshot`,`quantity`,`unitPrice`,`discount`,`subtotal`,`createdAt`",
            listOf("CREATE INDEX `index_sale_items_saleId` ON `sale_items` (`saleId`)", "CREATE INDEX `index_sale_items_productId` ON `sale_items` (`productId`)"))
    }

    private fun fixture(name: String, version: Int): File {
        names += name
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        db.openHelper.writableDatabase
        db.close()
        val path = context.getDatabasePath(name).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            val fk = raw.rawQuery("PRAGMA foreign_keys", null).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
            if (fk) raw.execSQL("PRAGMA foreign_keys=OFF")
            raw.beginTransaction()
            try {
                if (version < 5) reverseV5(raw)
                if (version < 4) reverseV4(raw)
                if (version < 3) reverseV3(raw)
                if (version < 2) raw.execSQL("DROP INDEX IF EXISTS `index_sales_transactionStatus_createdAt`")
                raw.setTransactionSuccessful()
            } finally { raw.endTransaction() }
            if (fk) raw.execSQL("PRAGMA foreign_keys=ON")
            raw.version = version
            raw.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        }
        return File(path)
    }

    private fun migrate(name: String) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        db.openHelper.writableDatabase
        assertEquals(5, db.openHelper.readableDatabase.version)
        db.close()
    }

    @Test fun v1_validate_acceptsAndMigrates() {
        val f = fixture("g3-v1", 1)
        assertNull(BackupService(context).validateStaged(f))
        migrate("g3-v1")
    }

    @Test fun v2_validate_acceptsAndMigrates() {
        val f = fixture("g3-v2", 2)
        assertNull(BackupService(context).validateStaged(f))
        migrate("g3-v2")
    }

    @Test fun v5_validate_accepts() {
        val f = fixture("g3-v5", 5)
        assertNull(BackupService(context).validateStaged(f))
    }

    // ---- cash movement reason required ----
    private fun scalar(db: AppDatabase, sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test fun cash_blankReason_rejectedForBothTypes() = runBlocking {
        val db = TestDb.inMemory()
        val admin = TestDb.admin(active = true).copy(id = 1)
        db.userDao().insert(admin)
        val shifts = TestDb.shifts(db)
        val open = shifts.open(admin, 500_000L)
        val shift = (open as ShiftRepository.Result.Ok).shift

        val inBlank = shifts.cash(shift, "CASH_IN", 10_000L, "", admin.id) as? ShiftRepository.Result.Error
        assertTrue("blank CASH_IN must be rejected", inBlank != null)
        assertTrue(inBlank?.message?.contains("Alasan") == true)

        val outBlank = shifts.cash(shift, "CASH_OUT", 1_000L, "   ", admin.id) as? ShiftRepository.Result.Error
        assertTrue("blank CASH_OUT must be rejected", outBlank != null)

        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM cash_movements"))
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM audit_logs WHERE action IN ('CASH_IN','CASH_OUT')"))
        db.close()
    }

    @Test fun cash_nonBlankReason_accepted() = runBlocking {
        val db = TestDb.inMemory()
        val admin = TestDb.admin(active = true).copy(id = 1)
        db.userDao().insert(admin)
        val shifts = TestDb.shifts(db)
        val shift = (shifts.open(admin, 500_000L) as ShiftRepository.Result.Ok).shift

        val res = shifts.cash(shift, "CASH_IN", 10_000L, "Setor modal", admin.id)
        assertTrue(res is ShiftRepository.Result.Ok)
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM cash_movements"))
        db.close()
    }

    // ---- atomic settings ----
    @Test fun posConfig_atomic_writesAllOrNothing() = runBlocking {
        val db = TestDb.inMemory()
        db.userDao().insert(TestDb.admin(active = true).copy(id = 1))
        val settings = TestDb.settings(db)

        assertNull(settings.savePosConfiguration("NEW", 10, 5, 100, 1))
        assertEquals("NEW", settings.raw("pos.invoice_prefix"))
        assertEquals("10", settings.raw("pos.tax_percent"))
        assertEquals("5", settings.raw("pos.service_percent"))
        assertEquals("100", settings.raw("pos.rounding"))

        db.userDao().update(TestDb.admin(active = false).copy(id = 1))
        assertNotNull("unauthorized write must fail", settings.savePosConfiguration("X", 1, 1, 1, 1))
        assertEquals("NEW", settings.raw("pos.invoice_prefix"))  // zero keys changed
        assertEquals("10", settings.raw("pos.tax_percent"))
        db.close()
    }

    @Test fun receiptConfig_atomic_writesAllOrNothing() = runBlocking {
        val db = TestDb.inMemory()
        db.userDao().insert(TestDb.admin(active = true).copy(id = 1))
        val settings = TestDb.settings(db)

        assertNull(settings.saveReceiptConfiguration("80", "Kaki Baru", true, false, true, 1))
        assertEquals("80mm", settings.raw("receipt.paper"))
        assertEquals("Kaki Baru", settings.raw("receipt.footer"))
        assertEquals("0", settings.raw("receipt.show_address"))

        db.userDao().update(TestDb.admin(active = false).copy(id = 1))
        assertNotNull(settings.saveReceiptConfiguration("58", "Y", true, true, true, 1))
        assertEquals("80mm", settings.raw("receipt.paper"))
        assertEquals("Kaki Baru", settings.raw("receipt.footer"))
        db.close()
    }

    // ---- Revision 01: restore reauth must clear the session synchronously ----

    /**
     * Proves the previous identity is dropped in the SAME call frame as
     * [AppViewModel.forceReauthAfterRestore] — before the suspending re-read of the restored
     * database can complete.
     *
     * There must never be a frame where the restored DB is live while the previously
     * authenticated user is still authoritative in SessionState.
     */
    @Test fun forceReauthAfterRestore_clearsUserSynchronously() {
        AppDatabase.closeAndClear()
        context.deleteDatabase(AppDatabase.NAME)
        try {
            val seeded = runBlocking {
                AppGraph.users.bootstrapAdmin("rev01admin", "Rev01 Admin", "Password123")
            }
            assertNull(seeded.error)

            val vm = AppViewModel()
            runBlocking { vm.login("rev01admin", "Password123").join() }
            assertNotNull("precondition: session must hold a user", vm.session.value.user)

            // Synchronous assertion: user is already null the moment the call returns.
            vm.forceReauthAfterRestore("Restore berhasil.")
            assertNull("stale identity survived forceReauthAfterRestore", vm.session.value.user)
        } finally {
            AppDatabase.closeAndClear()
            context.deleteDatabase(AppDatabase.NAME)
        }
    }
}
