package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the Track C transaction invariants hold under concurrency. Requires a device. */
@RunWith(AndroidJUnit4::class)
class ConcurrencyTest {

    private lateinit var db: com.trapezo.pos.data.database.AppDatabase
    private lateinit var admin: UserEntity

    @Before fun setUp() {
        runBlocking {
        db = TestDb.inMemory()
        admin = db.userDao().insert(TestDb.admin(active = true)).let { TestDb.admin(active = true).copy(id = it) }
        db.userDao().insert(TestDb.cashier(active = true))
    }
    }

    @After fun tearDown() { db.close() }

    @Test fun doubleShiftOpen_onlyOneSucceeds() {
        runBlocking {
        val shifts = TestDb.shifts(db)
        val results = withContext(Dispatchers.IO) {
            listOf(
                async { shifts.open(admin, 50_000L) },
                async { shifts.open(admin, 50_000L) }
            ).awaitAll()
        }
        val ok = results.count { it is com.trapezo.pos.data.repository.ShiftRepository.Result.Ok }
        assertEquals(1, ok)
        val openCount = db.shiftDao().anyOpenShift()?.let { 1 } ?: 0
        assertEquals(1, openCount)
    }
    }

    @Test fun concurrentStockRemoval_neverGoesNegative() {
        runBlocking {
        val products = TestDb.products(db)
        val saved = products.save(
            ProductEntity(name = "Item", sku = "SKU-C", trackInventory = true, stockQty = 5),
            userId = admin.id
        )
        assertTrue(saved.ok)
        val product = db.productDao().byId(saved.id)!!
        val actorId = admin.id
        val results = withContext(Dispatchers.IO) {
            listOf(
                async { products.adjustStock(product, "REMOVE", 3, "konkuren 1", actorId) },
                async { products.adjustStock(product, "REMOVE", 3, "konkuren 2", actorId) }
            ).awaitAll()
        }
        // Only the removal that fits remaining stock succeeds; stock never negative.
        val finalStock = db.productDao().stockOf(saved.id)!!
        assertTrue(finalStock >= 0)
        assertEquals(2L, finalStock)
        assertEquals(1, results.count { it })
    }
    }

    @Test fun concurrentStockRemoval_movementLedgerMatchesFinalStock() {
        runBlocking {
        val products = TestDb.products(db)
        val saved = products.save(
            ProductEntity(name = "Item", sku = "SKU-L", trackInventory = true, stockQty = 4),
            userId = admin.id
        )
        val product = db.productDao().byId(saved.id)!!
        val actorId = admin.id
        withContext(Dispatchers.IO) {
            listOf(
                async { products.adjustStock(product, "REMOVE", 4, "a", actorId) },
                async { products.adjustStock(product, "REMOVE", 4, "b", actorId) }
            ).awaitAll()
        }
        val finalStock = db.productDao().stockOf(saved.id)!!
        assertEquals(0L, finalStock)
        val movementSum = db.inventoryDao().recent(100, 0).sumOf { it.quantity }
        // INITIAL (+4) + one successful REMOVE (-4) must net to zero.
        assertEquals(finalStock, 0L)
        assertEquals(0L, movementSum)
    }
    }
}
