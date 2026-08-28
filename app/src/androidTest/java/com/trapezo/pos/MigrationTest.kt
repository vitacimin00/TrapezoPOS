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
 * the financial and single-open-shift backfills. Requires a device/emulator.
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

    @Test fun migrate1to5() {
        helper.createDatabase("mig-1-5", 1).close()
        helper.runMigrationsAndValidate(
            "mig-1-5", 5, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5
        ).close()
    }

    @Test fun migrate2to5_backfillsNetTotalToSaleGrandTotal() {
        val db = helper.createDatabase("mig-2-5", 2)
        db.execSQL(
            "INSERT INTO sales(id, invoiceNumber, userId, userNameSnapshot, subtotal, discount, tax, serviceCharge, grandTotal, paidAmount, changeAmount, paymentStatus, transactionStatus, createdAt) " +
                "VALUES(1, 'INV-1', 1, 'A', 10000, 0, 0, 0, 10000, 10000, 0, 'PAID', 'COMPLETED', 0)"
        )
        db.execSQL(
            "INSERT INTO sale_items(id, saleId, productNameSnapshot, barcodeSnapshot, quantity, unitPrice, discount, subtotal, createdAt) " +
                "VALUES(11, 1, 'ItemA', '', 2, 4000, 0, 8000, 0)"
        )
        db.execSQL(
            "INSERT INTO sale_items(id, saleId, productNameSnapshot, barcodeSnapshot, quantity, unitPrice, discount, subtotal, createdAt) " +
                "VALUES(12, 1, 'ItemB', '', 1, 2000, 0, 2000, 0)"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "mig-2-5", 5, true,
            AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5
        )
        migrated.query("SELECT SUM(netTotal) FROM sale_items WHERE saleId=1").use { c ->
            c.moveToFirst()
            assertEquals(10000L, c.getLong(0))
        }
        migrated.close()
    }

    @Test fun migrate3to5() {
        helper.createDatabase("mig-3-5", 3).close()
        helper.runMigrationsAndValidate(
            "mig-3-5", 5, true,
            AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5
        ).close()
    }

    @Test fun migrate4to5_repairsDuplicateOpenShifts() {
        val db = helper.createDatabase("mig-4-5", 4)
        // Two legacy OPEN shifts; migration must keep the latest and close the older one.
        db.execSQL(
            "INSERT INTO shifts(id, userId, userNameSnapshot, openingCash, totalCashSales, totalNonCashSales, cashIn, cashOut, expectedCash, actualCash, difference, openedAt, status) " +
                "VALUES(1, 1, 'A', 10000, 0, 0, 0, 0, 10000, 0, 0, 1000, 'OPEN')"
        )
        db.execSQL(
            "INSERT INTO shifts(id, userId, userNameSnapshot, openingCash, totalCashSales, totalNonCashSales, cashIn, cashOut, expectedCash, actualCash, difference, openedAt, status) " +
                "VALUES(2, 1, 'A', 10000, 0, 0, 0, 0, 10000, 0, 0, 2000, 'OPEN')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "mig-4-5", 5, true, AppDatabase.MIGRATION_4_5
        )
        migrated.query("SELECT COUNT(*) FROM shifts WHERE status='OPEN'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM shifts WHERE openGuard=1").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        migrated.close()
    }
}
