package com.trapezo.pos.data.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.trapezo.pos.TrapezoApp
import com.trapezo.pos.data.dao.CategoryDao
import com.trapezo.pos.data.dao.CustomerDao
import com.trapezo.pos.data.dao.InventoryDao
import com.trapezo.pos.data.dao.ProductDao
import com.trapezo.pos.data.dao.RefundDao
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.dao.SettingsDao
import com.trapezo.pos.data.dao.StoreDao
import com.trapezo.pos.data.dao.ShiftDao
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
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.SettingEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.StoreEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.utils.PasswordUtil

@Database(
    entities = [
        UserEntity::class,
        StoreEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        ProductBarcodeEntity::class,
        InventoryMovementEntity::class,
        CustomerEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        PaymentEntity::class,
        PaymentMethodEntity::class,
        ShiftEntity::class,
        CashMovementEntity::class,
        RefundEntity::class,
        RefundItemEntity::class,
        SettingEntity::class,
        AuditLogEntity::class
    ],
    version = 2,
    exportSchema = false
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

    companion object {

        const val NAME = "trapezo_pos.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(TrapezoApp.instance).also { instance = it }
        }

        /** Closes all SQLite handles before a file-level database restore. */
        fun closeAndClear() = synchronized(this) {
            try { instance?.close() } catch (_: Exception) { }
            instance = null
        }

        /**
         * Versioned, non-destructive schema evolution. Never fall back to wiping
         * a store database: a missing future migration should fail loudly rather
         * than silently deleting sales/inventory records.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sales_transactionStatus_createdAt " +
                        "ON sales(transactionStatus, createdAt)"
                )
            }
        }

        private fun build(app: android.content.Context): AppDatabase =
            Room.databaseBuilder(app, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(SeedCallback())
                .build()

        /**
         * First-run seeding ONLY: 1 admin user, default payment methods,
         * "Lainnya" category, default store row and default settings.
         * NO products are ever seeded here — the product database starts empty.
         */
        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val now = System.currentTimeMillis()
                val adminHash = PasswordUtil.hash("admin123")

                fun esc(s: String) = "'" + s.replace("'", "''") + "'"

                db.execSQL(
                    """INSERT INTO users(username,passwordHash,name,role,isActive,createdAt,updatedAt)
                       VALUES('admin',${esc(adminHash)},${esc("Administrator")},'ADMIN',1,$now,$now)""".trimMargin()
                )

                val methods = listOf(
                    listOf("Tunai", "CASH"),
                    listOf("QRIS", "QRIS"),
                    listOf("Transfer", "TRANSFER"),
                    listOf("Debit", "DEBIT"),
                    listOf("Kartu Kredit", "CREDIT_CARD"),
                    listOf("E-Wallet", "EWALLET"),
                    listOf("Lainnya", "OTHER")
                )
                for ((n, t) in methods) {
                    db.execSQL("INSERT INTO payment_methods(name,type,isActive) VALUES(${esc(n)},${esc(t)},1)")
                }

                db.execSQL("INSERT INTO categories(name,description,isActive,createdAt,updatedAt) VALUES('Lainnya','Kategori bawaan',1,$now,$now)")

                db.execSQL("INSERT INTO stores(name,address,phone,email,createdAt,updatedAt) VALUES(${esc("Toko Saya")},'', '', '', $now,$now)")

                val defaults = mapOf(
                    "store.name" to "",
                    "pos.tax_percent" to "0",
                    "pos.service_percent" to "0",
                    "pos.rounding" to "0",
                    "pos.invoice_prefix" to "INV",
                    "pos.invoice_seq" to "1",
                    "receipt.paper" to "80mm",
                    "receipt.show_logo" to "1",
                    "receipt.show_address" to "1",
                    "receipt.show_phone" to "1",
                    "receipt.footer" to "Terima kasih telah berbelanja!",
                    "printer.address" to "",
                    "printer.paper_width" to "80"
                )
                for ((k, v) in defaults) {
                    db.execSQL("INSERT INTO settings(`key`,value) VALUES(${esc(k)},${esc(v)})")
                }
            }
        }
    }
}
