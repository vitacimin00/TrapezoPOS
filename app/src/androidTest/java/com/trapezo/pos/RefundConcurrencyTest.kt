package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.repository.RefundRepository
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.OrderDiscount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Concurrent refunds against one sale must never exceed its quantity/value. */
@RunWith(AndroidJUnit4::class)
class RefundConcurrencyTest {

    private lateinit var db: com.trapezo.pos.data.database.AppDatabase
    private lateinit var admin: com.trapezo.pos.data.entity.UserEntity

    @Before fun setUp() = runBlocking {
        db = TestDb.inMemory()
        admin = db.userDao().insert(TestDb.admin(active = true)).let { TestDb.admin(active = true).copy(id = it) }
        db.paymentMethodDao().insert(com.trapezo.pos.data.entity.PaymentMethodEntity(name = "Tunai", type = "CASH", isActive = true))
    }

    @After fun tearDown() { db.close() }

    @Test fun concurrentRefunds_neverExceedSoldQuantityOrValue() = runBlocking {
        val products = TestDb.products(db)
        val saved = products.save(ProductEntity(name = "Item", sku = "RF-1", trackInventory = true, stockQty = 2, sellPrice = 10_000), userId = admin.id)
        val shift = TestDb.shifts(db).open(admin, 100_000L).let { (it as com.trapezo.pos.data.repository.ShiftRepository.Result.Ok).shift }
        val sales = TestDb.sales(db)
        val product = db.productDao().byId(saved.id)!!
        val line = CartLine(productId = product.id, name = product.name, barcode = null, unitPrice = 10_000, quantity = 2, trackInventory = true, stockQty = product.stockQty, taxFreeItem = false, nonServiceChargeItem = false)
        val checkout = sales.checkout(listOf(line), OrderDiscount(), linkedMapOf("CASH" to 20_000L), emptyMap(), admin, shift.id, null, null)
        val saleId = (checkout as SalesRepository.CheckoutResult.Success).sale.id
        val saleItems = db.saleDao().itemsFor(saleId)
        val saleItem = saleItems.first()

        val refunds = TestDb.refunds(db)
        val results = withContext(Dispatchers.IO) {
            listOf(
                async { refunds.refund(saleId, admin.id, listOf(RefundRepository.RequestedItem(saleItem, 2)), "refund A") },
                async { refunds.refund(saleId, admin.id, listOf(RefundRepository.RequestedItem(saleItem, 2)), "refund B") }
            ).awaitAll()
        }
        val succeeded = results.count { it is RefundRepository.Result.Success }
        // At most one full-quantity refund may succeed; cumulative must not exceed sold=2.
        assertTrue(succeeded <= 1)
        val totalRefundedQty = db.refundDao().refundedQtyFor(saleItem.id)
        assertTrue(totalRefundedQty <= 2)
        val totalRefundedAmount = db.refundDao().refundedAmountFor(saleItem.id)
        assertTrue(totalRefundedAmount <= saleItem.netTotal)
    }
}
