package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.CartEngine
import com.trapezo.pos.domain.model.CartLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CartEngineTest {
    private fun line(id: Long = 1, qty: Long = 1, stock: Long = 3, tracked: Boolean = true) = CartLine(
        productId = id, name = "Kopi", barcode = "8991", unitPrice = 10_000,
        quantity = qty, trackInventory = tracked, stockQty = stock,
        taxFreeItem = false, nonServiceChargeItem = false
    )

    @Test fun scanSameProduct_mergesQuantityIntoOneLine() {
        val first = CartEngine.add(emptyList(), line())
        val second = CartEngine.add(first.lines, line())
        assertTrue(second.accepted)
        assertEquals(1, second.lines.size)
        assertEquals(2, second.lines.single().quantity)
        assertEquals(20_000, second.lines.single().subtotal)
    }

    @Test fun addTrackedProductBeyondStock_rejectsWithoutChangingCart() {
        val cart = listOf(line(qty = 3, stock = 3))
        val result = CartEngine.add(cart, line(stock = 3))
        assertFalse(result.accepted)
        assertEquals(3, result.lines.single().quantity)
        assertTrue(result.message!!.contains("Stok"))
    }

    @Test fun updateQuantityToZero_removesLine() {
        val result = CartEngine.setQuantity(listOf(line()), 1, 0)
        assertTrue(result.accepted)
        assertTrue(result.lines.isEmpty())
    }

    @Test fun nonTrackedProduct_canIncreaseWithoutStockLimit() {
        val result = CartEngine.setQuantity(listOf(line(tracked = false, stock = 0)), 1, 100)
        assertTrue(result.accepted)
        assertEquals(100, result.lines.single().quantity)
    }
}
