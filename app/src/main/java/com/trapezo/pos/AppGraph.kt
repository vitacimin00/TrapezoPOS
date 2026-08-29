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

/**
 * Lightweight manual DI graph; keeps UI independent of Room construction.
 *
 * Getters intentionally do NOT cache Room/repositories: a restore replaces the
 * database file after AppDatabase.closeAndClear(), and these getters then bind
 * every subsequent UI action to the newly opened database.
 *
 * Each repository getter captures ONE database instance first and constructs every
 * DAO/repository for that graph node from that one instance. This avoids evaluating
 * AppDatabase.get() multiple times inside one repository construction, which could
 * theoretically mix DAOs from different instances if maintenance swaps the singleton
 * between those evaluations.
 */
object AppGraph {

    /** Raw database access (indexed lookup used by a few screens). Not cached. */
    val db: AppDatabase get() = AppDatabase.get()

    val settings: SettingsRepository
        get() = SettingsRepository(db)

    val products: ProductRepository
        get() {
            val database = db
            return ProductRepository(
                database,
                database.productDao(),
                database.categoryDao(),
                database.inventoryDao(),
                SettingsRepository(database)
            )
        }

    val sales: SalesRepository
        get() {
            val database = db
            return SalesRepository(database, database.saleDao(), SettingsRepository(database))
        }

    val customers: CustomerRepository
        get() {
            val database = db
            return CustomerRepository(database, database.customerDao(), SettingsRepository(database))
        }

    val refunds: RefundRepository
        get() = RefundRepository(db)

    val shifts: ShiftRepository
        get() = ShiftRepository(db)

    val store: StoreRepository
        get() = StoreRepository(db)

    val users: UserRepository
        get() {
            val database = db
            return UserRepository(database, database.userDao(), SettingsRepository(database))
        }
}
