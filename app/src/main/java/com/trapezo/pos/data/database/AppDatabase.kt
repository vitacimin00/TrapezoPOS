package com.trapezo.pos.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.trapezo.pos.TrapezoApp
import com.trapezo.pos.data.dao.CategoryDao
import com.trapezo.pos.data.dao.CustomerDao
import com.trapezo.pos.data.dao.InventoryDao
import com.trapezo.pos.data.dao.ProductDao
import com.trapezo.pos.data.dao.RefundDao
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.dao.SettingsDao
import com.trapezo.pos.data.dao.ShiftDao
import com.trapezo.pos.data.dao.StoreDao
import com.trapezo.pos.data.dao.UserDao
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.CustomerEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.PaymentMethodEntity
import com.trapezo.pos.data.entity.ProductBarcodeEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.RefundEntity
import com.trapezo.pos.data.entity.RefundItemEntity
import com.trapezo.pos.data.entity.RefundPaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.SettingEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.StoreEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.domain.model.RefundRules

@Database(
    entities = [
        UserEntity::class, StoreEntity::class, CategoryEntity::class, ProductEntity::class,
        ProductBarcodeEntity::class, InventoryMovementEntity::class, CustomerEntity::class,
        SaleEntity::class, SaleItemEntity::class, PaymentEntity::class, PaymentMethodEntity::class,
        ShiftEntity::class, CashMovementEntity::class, RefundEntity::class, RefundItemEntity::class,
        RefundPaymentEntity::class, SettingEntity::class, AuditLogEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun storeDao(): StoreDao
    abstract fun categoryDao(): CategoryDao
    abstract fun productDao(): ProductDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleDao(): SaleDao
    abstract fun refundDao(): RefundDao
    abstract fun shiftDao(): ShiftDao
    abstract fun settingsDao(): SettingsDao
    abstract fun paymentMethodDao(): com.trapezo.pos.data.dao.PaymentMethodDao

    companion object {
        const val NAME = "trapezo_pos.db"
        @Volatile private var instance: AppDatabase? = null
        private val maintenanceMonitor = Object()
        @Volatile private var maintenance = false

        fun get(): AppDatabase = synchronized(maintenanceMonitor) {
            while (maintenance) maintenanceMonitor.wait()
            instance ?: build(TrapezoApp.instance).also { instance = it }
        }

        /** Prevents Room from reopening while database files are maintained. */
        internal fun <T> withMaintenance(block: (AppDatabase) -> T): T {
            val current = synchronized(maintenanceMonitor) {
                while (maintenance) maintenanceMonitor.wait()
                val open = instance ?: build(TrapezoApp.instance).also { instance = it }
                maintenance = true
                open
            }
            return try {
                block(current)
            } finally {
                synchronized(maintenanceMonitor) {
                    maintenance = false
                    maintenanceMonitor.notifyAll()
                }
            }
        }

        fun closeAndClear() = synchronized(this) {
            try { instance?.close() } catch (_: Exception) { }
            instance = null
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_transactionStatus_createdAt ON sales(transactionStatus, createdAt)")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sale_items ADD COLUMN netTotal INTEGER NOT NULL DEFAULT 0")
                backfillSaleItemNetTotals(db)
                db.execSQL("ALTER TABLE refunds ADD COLUMN shiftId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_shiftId ON refunds(shiftId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `refund_payments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `refundId` INTEGER NOT NULL,
                        `method` TEXT NOT NULL,
                        `amount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`refundId`) REFERENCES `refunds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_refund_payments_refundId ON refund_payments(refundId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_refund_payments_method ON refund_payments(method)")
                backfillRefundPayments(db)
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN failedLoginCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN lockedUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Repairs any historical duplicate OPEN shifts and installs a hard database
         * invariant: only one row may carry openGuard=1; CLOSED rows use NULL.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN openGuard INTEGER DEFAULT NULL")
                var keepOpenId: Long? = null
                db.query("SELECT id FROM shifts WHERE status='OPEN' ORDER BY openedAt DESC, id DESC LIMIT 1").use { c ->
                    if (c.moveToFirst()) keepOpenId = c.getLong(0)
                }
                val keep = keepOpenId
                if (keep != null) {
                    db.execSQL(
                        """UPDATE shifts SET status='CLOSED', openGuard=NULL,
                           closedAt=COALESCE(closedAt, openedAt), actualCash=expectedCash, difference=0
                           WHERE status='OPEN' AND id<>?""".trimIndent(),
                        arrayOf(keep)
                    )
                    db.execSQL("UPDATE shifts SET openGuard=1 WHERE id=?", arrayOf(keep))
                }
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shifts_openGuard ON shifts(openGuard)")
            }
        }

        private fun backfillSaleItemNetTotals(db: SupportSQLiteDatabase) {
            db.query("SELECT id, grandTotal FROM sales ORDER BY id ASC").use { sales ->
                val saleIdCol = sales.getColumnIndexOrThrow("id")
                val grandCol = sales.getColumnIndexOrThrow("grandTotal")
                while (sales.moveToNext()) {
                    val saleId = sales.getLong(saleIdCol)
                    val grandTotal = sales.getLong(grandCol).coerceAtLeast(0)
                    val itemIds = mutableListOf<Long>()
                    val weights = mutableListOf<Long>()
                    db.query("SELECT id, subtotal FROM sale_items WHERE saleId=? ORDER BY id ASC", arrayOf(saleId)).use { items ->
                        val idCol = items.getColumnIndexOrThrow("id")
                        val subtotalCol = items.getColumnIndexOrThrow("subtotal")
                        while (items.moveToNext()) {
                            itemIds += items.getLong(idCol)
                            weights += items.getLong(subtotalCol).coerceAtLeast(0)
                        }
                    }
                    if (itemIds.isEmpty()) continue
                    val allocation = RefundRules.allocateTotal(grandTotal, weights)
                    itemIds.forEachIndexed { index, itemId ->
                        db.execSQL("UPDATE sale_items SET netTotal=? WHERE id=?", arrayOf(allocation[index], itemId))
                    }
                }
            }
        }

        private fun backfillRefundPayments(db: SupportSQLiteDatabase) {
            val originalCache = mutableMapOf<Long, LinkedHashMap<String, Long>>()
            val alreadyBySale = mutableMapOf<Long, MutableMap<String, Long>>()
            fun originalMethods(saleId: Long): LinkedHashMap<String, Long> = originalCache.getOrPut(saleId) {
                var grandTotal = 0L
                db.query("SELECT grandTotal FROM sales WHERE id=? LIMIT 1", arrayOf(saleId)).use { c ->
                    if (c.moveToFirst()) grandTotal = c.getLong(0).coerceAtLeast(0)
                }
                val raw = LinkedHashMap<String, Long>()
                db.query("SELECT method, SUM(amount) FROM payments WHERE saleId=? GROUP BY method ORDER BY MIN(id) ASC", arrayOf(saleId)).use { c ->
                    while (c.moveToNext()) {
                        val method = c.getString(0)
                        val amount = c.getLong(1).coerceAtLeast(0)
                        if (amount > 0) raw[method] = amount
                    }
                }
                if (raw.isEmpty() && grandTotal > 0) raw["OTHER"] = grandTotal
                val normalized = LinkedHashMap<String, Long>()
                if (raw.isNotEmpty()) {
                    val values = RefundRules.allocateTotal(grandTotal, raw.values.toList())
                    raw.keys.forEachIndexed { index, method -> normalized[method] = values[index] }
                }
                normalized
            }

            db.query("SELECT id, saleId, total, createdAt FROM refunds ORDER BY id ASC").use { refunds ->
                val idCol = refunds.getColumnIndexOrThrow("id")
                val saleCol = refunds.getColumnIndexOrThrow("saleId")
                val totalCol = refunds.getColumnIndexOrThrow("total")
                val createdCol = refunds.getColumnIndexOrThrow("createdAt")
                while (refunds.moveToNext()) {
                    val refundId = refunds.getLong(idCol)
                    val saleId = refunds.getLong(saleCol)
                    val total = refunds.getLong(totalCol).coerceAtLeast(0)
                    val createdAt = refunds.getLong(createdCol)
                    if (total == 0L) continue
                    val original = originalMethods(saleId)
                    val already = alreadyBySale.getOrPut(saleId) { mutableMapOf() }
                    val remainingCapacity = original.entries.sumOf { (method, amount) -> (amount - (already[method] ?: 0L)).coerceAtLeast(0) }
                    val allocation = if (original.isNotEmpty() && remainingCapacity >= total) {
                        RefundRules.allocateRefundByRemainingCapacity(total, original, already)
                    } else linkedMapOf("OTHER" to total)
                    allocation.forEach { (method, amount) ->
                        db.execSQL("INSERT INTO refund_payments(refundId,method,amount,createdAt) VALUES(?,?,?,?)", arrayOf(refundId, method, amount, createdAt))
                        already[method] = (already[method] ?: 0L) + amount
                    }
                }
            }
        }

        private fun build(app: android.content.Context): AppDatabase =
            Room.databaseBuilder(app, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(SeedCallback())
                .build()

        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val now = System.currentTimeMillis()
                fun esc(s: String) = "'" + s.replace("'", "''") + "'"
                val methods = listOf(
                    "Tunai" to "CASH", "QRIS" to "QRIS", "Transfer" to "TRANSFER",
                    "Debit" to "DEBIT", "Kartu Kredit" to "CREDIT_CARD",
                    "E-Wallet" to "EWALLET", "Lainnya" to "OTHER"
                )
                for ((name, type) in methods) {
                    db.execSQL("INSERT INTO payment_methods(name,type,isActive) VALUES(${esc(name)},${esc(type)},1)")
                }
                db.execSQL("INSERT INTO categories(name,description,isActive,createdAt,updatedAt) VALUES('Lainnya','Kategori bawaan',1,$now,$now)")
                db.execSQL("INSERT INTO stores(name,address,phone,email,createdAt,updatedAt) VALUES(${esc("Toko Saya")},'', '', '', $now,$now)")
                val defaults = mapOf(
                    "store.name" to "", "pos.tax_percent" to "0", "pos.service_percent" to "0",
                    "pos.rounding" to "0", "pos.invoice_prefix" to "INV", "pos.invoice_seq" to "1",
                    "customer.seq" to "1", "sku.seq" to "1", "receipt.paper" to "80mm",
                    "receipt.show_logo" to "1", "receipt.show_address" to "1", "receipt.show_phone" to "1",
                    "receipt.footer" to "Terima kasih telah berbelanja!", "printer.address" to "", "printer.paper_width" to "80"
                )
                for ((key, value) in defaults) db.execSQL("INSERT INTO settings(`key`,value) VALUES(${esc(key)},${esc(value)})")
            }
        }
    }
}
