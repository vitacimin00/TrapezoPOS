package com.trapezo.pos

import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.repository.ProductRepository
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.data.repository.SettingsRepository
import com.trapezo.pos.data.repository.CustomerRepository
import com.trapezo.pos.data.repository.RefundRepository
import com.trapezo.pos.data.repository.ShiftRepository
import com.trapezo.pos.data.repository.StoreRepository
import com.trapezo.pos.data.repository.UserRepository

/** Lightweight manual DI graph; keeps UI independent of Room construction. */
object AppGraph {
    /**
     * Getters intentionally do not cache Room/repositories. A restore replaces the
     * database file after AppDatabase.closeAndClear(), and these getters then bind
     * every subsequent UI action to the newly opened database.
     */
    val db: AppDatabase get() = AppDatabase.get()
    val settings: SettingsRepository get() = SettingsRepository(db.settingsDao())
    val products: ProductRepository get() = ProductRepository(db, db.productDao(), db.categoryDao(), db.inventoryDao(), settings)
    val sales: SalesRepository get() = SalesRepository(db, db.saleDao(), settings)
    val customers: CustomerRepository get() = CustomerRepository(db.customerDao(), settings)
    val refunds: RefundRepository get() = RefundRepository(db)
    val shifts: ShiftRepository get() = ShiftRepository(db)
    val store: StoreRepository get() = StoreRepository(db.storeDao())
    val users: UserRepository get() = UserRepository(db.userDao(), settings)
}
