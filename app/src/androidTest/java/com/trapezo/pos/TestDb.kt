package com.trapezo.pos

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.ProductRepository
import com.trapezo.pos.data.repository.RefundRepository
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.data.repository.SettingsRepository
import com.trapezo.pos.data.repository.ShiftRepository
import com.trapezo.pos.data.repository.StoreRepository
import com.trapezo.pos.data.repository.UserRepository

/** Shared in-memory database helpers for repository-level (device) tests. */
object TestDb {

    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    fun inMemory(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun settings(db: AppDatabase) = SettingsRepository(db)
    fun products(db: AppDatabase) =
        ProductRepository(db, db.productDao(), db.categoryDao(), db.inventoryDao(), settings(db))
    fun store(db: AppDatabase) = StoreRepository(db)
    fun users(db: AppDatabase) = UserRepository(db, db.userDao(), settings(db))
    fun shifts(db: AppDatabase) = ShiftRepository(db)
    fun sales(db: AppDatabase) = SalesRepository(db, db.saleDao(), settings(db))
    fun refunds(db: AppDatabase) = RefundRepository(db)

    fun admin(active: Boolean = true) =
        UserEntity(username = "admin", passwordHash = "h", name = "Admin", role = "ADMIN", isActive = active)

    fun cashier(active: Boolean = true) =
        UserEntity(username = "cashier", passwordHash = "h", name = "Cashier", role = "CASHIER", isActive = active)
}
