package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.OrderDiscount
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

/** Critical checkout/refund/invoice concurrency guarantees. Requires a device. */
@RunWith(AndroidJUnit4::class)
class CheckoutConcurrencyTest {

    private lateinit var db: com.trapezo.pos.data.database.AppDatabase
    private lateinit var admin: com.trapezo.pos.data.entity.UserEntity

    @Before fun setUp() = runBlocking {
        db = TestDb.inMemory()
        admin = db.userDao().insert(TestDb.admin(active = true)).let { TestDb.admin(active = true).copy(id = it) }
        db.paymentMethodDao().insert(com.trapezo.pos.data.entity.PaymentMethodEntity(name = "Tunai", type = "CASH", isActive = true))
    }

    @After fun tearDown() { db.close() }

    private suspend fun openShift() = TestDb.shifts(db).open(admin, 100_000L).let { (it as com.trapezo.pos.data.repository.ShiftRepository.Result.Ok).shift }

    @Test fun concurrentCheckout_onlyOneSucceedsForLastStock() = runBlocking {
        val products = TestDb.products(db)
        val saved = products.save(ProductEntity(name = "Item", sku = "CC-1", trackInventory = true, stockQty = 1, sellPrice = 10_000), userId = admin.id)
        val shift = openShift()
        val sales = TestDb.sales(db)
        val product = db.productDao().byId(saved.id)!!
        val line = CartLine(productId = product.id, name = product.name, barcode = null, unitPrice = 10_000, quantity = 1, trackInventory = true, stockQty = product.stockQty, taxFreeItem = false, nonServiceChargeItem = false)

        val results = withContext(Dispatchers.IO) {
            listOf(
                async { sales.checkout(listOf(line), OrderDiscount(), linkedMapOf("CASH" to 10_000L), emptyMap(), admin, shift.id, null, null) },
                async { sales.checkout(listOf(line), OrderDiscount(), linkedMapOf("CASH" to 10_000L), emptyMap(), admin, shift.id, null, null) }
            ).awaitAll()
        }
        val success = results.count { it is SalesRepository.CheckoutResult.Success }
        assertEquals(1, success)
        assertEquals(0L, db.productDao().stockOf(saved.id))
        assertEquals(1, db.saleDao().itemsFor(1).size)
    }

    @Test fun concurrentCheckout_invoicesAreUnique() = runBlocking {
        val products = TestDb.products(db)
        val saved = products.save(ProductEntity(name = "Item", sku = "CC-2", trackInventory = true, stockQty = 100, sellPrice = 5_000), userId = admin.id)
        val shift = openShift()
        val sales = TestDb.sales(db)
        val product = db.productDao().byId(saved.id)!!
        val line = CartLine(productId = product.id, name = product.name, barcode = null, unitPrice = 5_000, quantity = 1, trackInventory = true, stockQty = product.stockQty, taxFreeItem = false, nonServiceChargeItem = false)

        val results = withContext(Dispatchers.IO) {
            (1..8).map {
                async { sales.checkout(listOf(line), OrderDiscount(), linkedMapOf("CASH" to 5_000L), emptyMap(), admin, shift.id, null, null) }
            }.awaitAll()
        }
        val invoices = results.filterIsInstance<SalesRepository.CheckoutResult.Success>().map { it.invoice }
        assertEquals(invoices.size, invoices.toSet().size) // all unique
        assertTrue(invoices.isNotEmpty())
    }
}
