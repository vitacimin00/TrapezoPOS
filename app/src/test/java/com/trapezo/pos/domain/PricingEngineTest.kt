package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.DiscountKind
import com.trapezo.pos.domain.model.OrderDiscount
import com.trapezo.pos.domain.model.PricingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingEngineTest {
    private fun line(
        id: Long,
        price: Long,
        qty: Long = 1,
        taxFree: Boolean = false,
        noService: Boolean = false
    ) = CartLine(
        productId = id,
        name = "P$id",
        barcode = null,
        unitPrice = price,
        quantity = qty,
        trackInventory = true,
        stockQty = 99,
        taxFreeItem = taxFree,
        nonServiceChargeItem = noService
    )

    @Test fun fiftyPercentOrderDiscountPersistsHalfValueForRefund() {
        val result = PricingEngine.price(
            lines = listOf(line(1, 100_000)),
            discount = OrderDiscount(DiscountKind.PERCENT, 50),
            taxPercent = 0,
            servicePercent = 0,
            roundingStep = 0
        )
        assertEquals(50_000L, result.totals.grandTotal)
        assertEquals(listOf(50_000L), result.lineNetTotals)
        assertEquals(result.totals.grandTotal, result.lineNetTotals.sum())
    }

    @Test fun taxFreeLineDoesNotAbsorbTaxCharge() {
        val result = PricingEngine.price(
            lines = listOf(line(1, 100_000), line(2, 100_000, taxFree = true)),
            discount = OrderDiscount(),
            taxPercent = 10,
            servicePercent = 0,
            roundingStep = 0
        )
        assertEquals(10_000L, result.totals.tax)
        assertEquals(210_000L, result.lineNetTotals.sum())
        assertTrue(result.lineNetTotals[0] > result.lineNetTotals[1])
    }

    @Test fun nonServiceLineDoesNotAbsorbServiceCharge() {
        val result = PricingEngine.price(
            lines = listOf(line(1, 100_000), line(2, 100_000, noService = true)),
            discount = OrderDiscount(),
            taxPercent = 0,
            servicePercent = 10,
            roundingStep = 0
        )
        assertEquals(10_000L, result.totals.serviceCharge)
        assertEquals(210_000L, result.lineNetTotals.sum())
        assertTrue(result.lineNetTotals[0] > result.lineNetTotals[1])
    }

    @Test fun roundedGrandTotalStillEqualsSumOfLineSnapshots() {
        val result = PricingEngine.price(
            lines = listOf(line(1, 1_111), line(2, 2_222)),
            discount = OrderDiscount(),
            taxPercent = 0,
            servicePercent = 0,
            roundingStep = 100
        )
        assertEquals(3_300L, result.totals.grandTotal)
        assertEquals(result.totals.grandTotal, result.lineNetTotals.sum())
    }
}
