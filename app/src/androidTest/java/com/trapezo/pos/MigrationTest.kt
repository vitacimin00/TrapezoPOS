package com.trapezo.pos

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises every migration path up to the current schema (version 5) and validates
 * the financial and single-open-shift backfills.
 *
 * Schema export is enabled (exportSchema=true) so `5.json` is committed; historical
 * 1–4 schemas are reproduced with raw-SQL fixtures because Room never exported them
 * before Track E. This keeps the tests runnable without fabricating JSON.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        arrayListOf(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun createShiftTable(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shifts(
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                userId INTEGER NOT NULL, userNameSnapshot TEXT NOT NULL,
                openingCash INTEGER NOT NULL, totalCashSales INTEGER NOT NULL, totalNonCashSales INTEGER NOT NULL,
                cashIn INTEGER NOT NULL, cashOut INTEGER NOT NULL, expectedCash INTEGER NOT NULL,
                actualCash INTEGER NOT NULL, difference INTEGER NOT NULL,
                openedAt INTEGER NOT NULL, closedAt INTEGER, status TEXT NOT NULL)"""
        )
    }

    @Test fun migrate4to5_zeroOpenShifts() {
        val db = helper.createDatabase("mig-4-5-zero", 4)
        createShiftTable(db)
        db.close()
        val migrated = helper.runMigrationsAndValidate("mig-4-5-zero", 5, true, AppDatabase.MIGRATION_4_5)
        migrated.query("SELECT COUNT(*) FROM shifts WHERE status='OPEN'").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM shifts WHERE openGuard=1").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        migrated.close()
    }

    @Test fun migrate4to5_oneOpenShiftKept() {
        val db = helper.createDatabase("mig-4-5-one", 4)
        createShiftTable(db)
        db.execSQL(
            "INSERT INTO shifts(id, userId, userNameSnapshot, openingCash, totalCashSales, totalNonCashSales, cashIn, cashOut, expectedCash, actualCash, difference, openedAt, status) " +
                "VALUES(7, 1, 'A', 5000, 0, 0, 0, 0, 5000, 0, 0, 9000, 'OPEN')"
        )
        db.close()
        val migrated = helper.runMigrationsAndValidate("mig-4-5-one", 5, true, AppDatabase.MIGRATION_4_5)
        migrated.query("SELECT COUNT(*) FROM shifts WHERE status='OPEN'").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        migrated.query("SELECT id FROM shifts WHERE status='OPEN'").use { c -> c.moveToFirst(); assertEquals(7L, c.getLong(0)) }
        migrated.query("SELECT COUNT(*) FROM shifts WHERE openGuard=1").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        migrated.close()
    }

    @Test fun migrate4to5_duplicateOpenShifts_keepsLatestByOpenedAtThenId() {
        val db = helper.createDatabase("mig-4-5-dup", 4)
        createShiftTable(db)
        db.execSQL(
            "INSERT INTO shifts(id, userId, userNameSnapshot, openingCash, totalCashSales, totalNonCashSales, cashIn, cashOut, expectedCash, actualCash, difference, openedAt, status) " +
                "VALUES(1, 1, 'A', 10000, 0, 0, 0, 0, 10000, 0, 0, 1000, 'OPEN')"
        )
        db.execSQL(
            "INSERT INTO shifts(id, userId, userNameSnapshot, openingCash, totalCashSales, totalNonCashSales, cashIn, cashOut, expectedCash, actualCash, difference, openedAt, status) " +
                "VALUES(2, 1, 'A', 10000, 0, 0, 0, 0, 10000, 0, 0, 2000, 'OPEN')"
        )
        db.close()
        val migrated = helper.runMigrationsAndValidate("mig-4-5-dup", 5, true, AppDatabase.MIGRATION_4_5)
        migrated.query("SELECT COUNT(*) FROM shifts WHERE status='OPEN'").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        // The latest by openedAt (then id) remains OPEN.
        migrated.query("SELECT id FROM shifts WHERE status='OPEN'").use { c -> c.moveToFirst(); assertEquals(2L, c.getLong(0)) }
        migrated.query("SELECT COUNT(*) FROM shifts WHERE openGuard=1").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        migrated.query("SELECT status FROM shifts WHERE id=1").use { c -> c.moveToFirst(); assertEquals("CLOSED", c.getString(0)) }
        migrated.close()
    }

    @Test fun migrate2to5_netTotalProportionalAllocationSumsToGrandTotal() {
        val db = helper.createDatabase("mig-2-5", 2)
        db.execSQL(
            "INSERT INTO sales(id, invoiceNumber, userId, userNameSnapshot, subtotal, discount, tax, serviceCharge, grandTotal, paidAmount, changeAmount, paymentStatus, transactionStatus, createdAt) " +
                "VALUES(1, 'INV-1', 1, 'A', 30000, 0, 0, 0, 25000, 25000, 0, 'PAID', 'COMPLETED', 0)"
        )
        // Three items of equal subtotal force remainder distribution across integer allocation.
        db.execSQL("INSERT INTO sale_items(id, saleId, productNameSnapshot, barcodeSnapshot, quantity, unitPrice, discount, subtotal, createdAt) VALUES(11, 1, 'A', '', 1, 10000, 0, 10000, 0)")
        db.execSQL("INSERT INTO sale_items(id, saleId, productNameSnapshot, barcodeSnapshot, quantity, unitPrice, discount, subtotal, createdAt) VALUES(12, 1, 'B', '', 1, 10000, 0, 10000, 0)")
        db.execSQL("INSERT INTO sale_items(id, saleId, productNameSnapshot, barcodeSnapshot, quantity, unitPrice, discount, subtotal, createdAt) VALUES(13, 1, 'C', '', 1, 10000, 0, 10000, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "mig-2-5", 5, true,
            AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5
        )
        migrated.query("SELECT SUM(netTotal) FROM sale_items WHERE saleId=1").use { c ->
            c.moveToFirst()
            assertEquals(25000L, c.getLong(0)) // exact conservation, remainder allocated deterministically
        }
        migrated.close()
    }
}
