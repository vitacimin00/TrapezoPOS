package com.trapezo.pos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.data.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Migration tests built from a real current Room database, never fabricated schema JSON. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = mutableListOf<AppDatabase>()
    private val names = mutableListOf<String>()

    @After fun tearDown() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun copyTable(
        db: SQLiteDatabase,
        table: String,
        createCopy: String,
        columns: String,
        indexes: List<String>
    ) {
        val copy = "${table}_fixture_copy"
        db.execSQL("DROP TABLE IF EXISTS `$copy`")
        db.execSQL(createCopy)
        db.execSQL("INSERT INTO `$copy` ($columns) SELECT $columns FROM `$table`")
        db.execSQL("DROP TABLE `$table`")
        db.execSQL("ALTER TABLE `$copy` RENAME TO `$table`")
        indexes.forEach(db::execSQL)
    }

    private fun reverseV5(db: SQLiteDatabase) = copyTable(
        db,
        table = "shifts",
        createCopy = """CREATE TABLE `shifts_fixture_copy` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `userId` INTEGER NOT NULL, `userNameSnapshot` TEXT NOT NULL,
            `openingCash` INTEGER NOT NULL, `totalCashSales` INTEGER NOT NULL,
            `totalNonCashSales` INTEGER NOT NULL, `cashIn` INTEGER NOT NULL,
            `cashOut` INTEGER NOT NULL, `expectedCash` INTEGER NOT NULL,
            `actualCash` INTEGER NOT NULL, `difference` INTEGER NOT NULL,
            `openedAt` INTEGER NOT NULL, `closedAt` INTEGER, `status` TEXT NOT NULL
        )""".trimIndent(),
        columns = "`id`, `userId`, `userNameSnapshot`, `openingCash`, `totalCashSales`, " +
            "`totalNonCashSales`, `cashIn`, `cashOut`, `expectedCash`, `actualCash`, " +
            "`difference`, `openedAt`, `closedAt`, `status`",
        indexes = listOf(
            "CREATE INDEX `index_shifts_userId` ON `shifts` (`userId`)",
            "CREATE INDEX `index_shifts_status` ON `shifts` (`status`)",
            "CREATE INDEX `index_shifts_openedAt` ON `shifts` (`openedAt`)"
        )
    )

    private fun reverseV4(db: SQLiteDatabase) = copyTable(
        db,
        table = "users",
        createCopy = """CREATE TABLE `users_fixture_copy` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `username` TEXT NOT NULL, `passwordHash` TEXT NOT NULL,
            `name` TEXT NOT NULL, `role` TEXT NOT NULL, `isActive` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
        )""".trimIndent(),
        columns = "`id`, `username`, `passwordHash`, `name`, `role`, `isActive`, `createdAt`, `updatedAt`",
        indexes = listOf("CREATE UNIQUE INDEX `index_users_username` ON `users` (`username`)")
    )

    private fun reverseV3(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE `refund_payments`")
        copyTable(
            db,
            table = "refunds",
            createCopy = """CREATE TABLE `refunds_fixture_copy` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `saleId` INTEGER NOT NULL, `userId` INTEGER NOT NULL,
                `total` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
            columns = "`id`, `saleId`, `userId`, `total`, `reason`, `createdAt`",
            indexes = listOf("CREATE INDEX `index_refunds_saleId` ON `refunds` (`saleId`)")
        )
        copyTable(
            db,
            table = "sale_items",
            createCopy = """CREATE TABLE `sale_items_fixture_copy` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `saleId` INTEGER NOT NULL, `productId` INTEGER,
                `productNameSnapshot` TEXT NOT NULL, `barcodeSnapshot` TEXT NOT NULL,
                `quantity` INTEGER NOT NULL, `unitPrice` INTEGER NOT NULL,
                `discount` INTEGER NOT NULL, `subtotal` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
            columns = "`id`, `saleId`, `productId`, `productNameSnapshot`, `barcodeSnapshot`, " +
                "`quantity`, `unitPrice`, `discount`, `subtotal`, `createdAt`",
            indexes = listOf(
                "CREATE INDEX `index_sale_items_saleId` ON `sale_items` (`saleId`)",
                "CREATE INDEX `index_sale_items_productId` ON `sale_items` (`productId`)"
            )
        )
    }

    /** Creates Room's v5 schema, then reverses only the known migration deltas. */
    private fun fixture(name: String, version: Int, seed: (SQLiteDatabase) -> Unit = {}): String {
        names += name
        context.deleteDatabase(name)
        val current = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        current.openHelper.writableDatabase
        current.close()
        val path = context.getDatabasePath(name).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val foreignKeysEnabled = db.rawQuery("PRAGMA foreign_keys", null).use { c ->
                c.moveToFirst() && c.getInt(0) != 0
            }
            if (foreignKeysEnabled) db.execSQL("PRAGMA foreign_keys=OFF")
            try {
                db.beginTransaction()
                try {
                    if (version < 5) reverseV5(db)
                    if (version < 4) reverseV4(db)
                    if (version < 3) reverseV3(db)
                    if (version < 2) db.execSQL("DROP INDEX IF EXISTS `index_sales_transactionStatus_createdAt`")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                if (foreignKeysEnabled) db.execSQL("PRAGMA foreign_keys=ON")
            }
            seed(db)
            db.version = version
        }
        return name
    }

    private fun migrate(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
        .build().also { it.openHelper.writableDatabase; opened += it }

    private fun AppDatabase.long(sql: String): Long = openHelper.readableDatabase.query(sql).use { c ->
        assertTrue(c.moveToFirst()); c.getLong(0)
    }
    private fun AppDatabase.text(sql: String): String = openHelper.readableDatabase.query(sql).use { c ->
        assertTrue(c.moveToFirst()); c.getString(0)
    }
    private fun AppDatabase.hasIndex(name: String) = long("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$name'") == 1L

    @Test fun migrate1to5_addsSalesIndexAndAllLaterSchema() {
        val db = migrate(fixture("mig-1-5", 1))
        assertTrue(db.hasIndex("index_sales_transactionStatus_createdAt"))
        assertTrue(db.hasIndex("index_refunds_shiftId"))
        assertTrue(db.hasIndex("index_shifts_openGuard"))
        assertEquals(5, db.openHelper.readableDatabase.version)
    }

    @Test fun migrate2to5_backfillsNetTotalsAndRefundPaymentsExactly() {
        val db = migrate(fixture("mig-2-5", 2) { raw ->
            raw.execSQL("INSERT INTO users(id,username,passwordHash,name,role,isActive,createdAt,updatedAt) VALUES(1,'a','h','A','ADMIN',1,0,0)")
            raw.execSQL("INSERT INTO sales(id,invoiceNumber,userId,userNameSnapshot,subtotal,discount,tax,serviceCharge,grandTotal,paidAmount,changeAmount,paymentStatus,transactionStatus,notes,createdAt) VALUES(1,'INV-1',1,'A',30000,0,0,0,25000,25000,0,'PAID','COMPLETED','',0)")
            raw.execSQL("INSERT INTO sale_items(id,saleId,productNameSnapshot,barcodeSnapshot,quantity,unitPrice,discount,subtotal,createdAt) VALUES(11,1,'A','',1,10000,0,10000,0),(12,1,'B','',1,10000,0,10000,0),(13,1,'C','',1,10000,0,10000,0)")
            raw.execSQL("INSERT INTO payments(id,saleId,method,amount,referenceNumber,createdAt) VALUES(1,1,'CASH',15000,'',0),(2,1,'QRIS',10000,'',0)")
            raw.execSQL("INSERT INTO refunds(id,saleId,userId,total,reason,createdAt) VALUES(1,1,1,10000,'r',1)")
        })
        assertEquals(25000L, db.long("SELECT SUM(netTotal) FROM sale_items WHERE saleId=1"))
        assertEquals("8334,8333,8333", db.text("SELECT group_concat(netTotal, ',') FROM (SELECT netTotal FROM sale_items WHERE saleId=1 ORDER BY id)"))
        assertEquals(10000L, db.long("SELECT SUM(amount) FROM refund_payments WHERE refundId=1"))
        assertEquals("CASH:6000,QRIS:4000", db.text("SELECT group_concat(method || ':' || amount, ',') FROM (SELECT method,amount FROM refund_payments WHERE refundId=1 ORDER BY id)"))
    }

    @Test fun migrate3to5_backfillsLoginGuardDefaults() {
        val db = migrate(fixture("mig-3-5", 3) { raw ->
            raw.execSQL("INSERT INTO users(id,username,passwordHash,name,role,isActive,createdAt,updatedAt) VALUES(9,'u','h','U','CASHIER',1,0,0)")
        })
        assertEquals(0L, db.long("SELECT failedLoginCount FROM users WHERE id=9"))
        assertEquals(0L, db.long("SELECT lockedUntil FROM users WHERE id=9"))
    }

    @Test fun migrate4to5_zeroOpenShifts() {
        val db = migrate(fixture("mig-4-5-zero", 4))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM shifts WHERE status='OPEN'"))
        assertEquals(0L, db.long("SELECT COUNT(*) FROM shifts WHERE openGuard=1"))
    }

    @Test fun migrate4to5_oneOpenShiftKept() {
        val db = migrate(fixture("mig-4-5-one", 4) { insertShift(it, 7, 9000) })
        assertEquals(7L, db.long("SELECT id FROM shifts WHERE status='OPEN'"))
        assertEquals(1L, db.long("SELECT COUNT(*) FROM shifts WHERE openGuard=1"))
    }

    @Test fun migrate4to5_duplicateOpenShifts_keepsLatestByOpenedAtThenId() {
        val db = migrate(fixture("mig-4-5-dup", 4) { raw ->
            insertShift(raw, 1, 1000); insertShift(raw, 2, 2000); insertShift(raw, 3, 2000)
        })
        assertEquals(1L, db.long("SELECT COUNT(*) FROM shifts WHERE status='OPEN'"))
        assertEquals(3L, db.long("SELECT id FROM shifts WHERE status='OPEN'"))
        assertEquals("CLOSED", db.text("SELECT status FROM shifts WHERE id=1"))
        assertEquals(1000L, db.long("SELECT closedAt FROM shifts WHERE id=1"))
        assertFalse(db.openHelper.readableDatabase.query("SELECT openGuard FROM shifts WHERE id=1").use { c -> c.moveToFirst(); !c.isNull(0) })
    }

    private fun insertShift(db: SQLiteDatabase, id: Long, openedAt: Long) {
        db.execSQL("INSERT INTO shifts(id,userId,userNameSnapshot,openingCash,totalCashSales,totalNonCashSales,cashIn,cashOut,expectedCash,actualCash,difference,openedAt,closedAt,status) VALUES(?,1,'A',5000,0,0,0,0,5000,0,0,?,NULL,'OPEN')", arrayOf(id, openedAt))
    }
}
