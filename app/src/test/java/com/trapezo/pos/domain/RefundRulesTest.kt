package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.RefundRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundRulesTest {
    @Test fun partialRefundOfOneOutOfTwo_isNotFullyRefunded() {
        assertFalse(RefundRules.isLineFullyRefunded(sold = 2, previouslyRefunded = 0, requested = 1))
    }

    @Test fun priorAndNewRefundEqualSoldQuantity_isFullyRefunded() {
        assertTrue(RefundRules.isLineFullyRefunded(sold = 3, previouslyRefunded = 1, requested = 2))
    }

    @Test fun requestedRefundCannotExceedRemainingQuantity() {
        assertFalse(RefundRules.isValidRequest(sold = 3, previouslyRefunded = 2, requested = 2))
        assertTrue(RefundRules.isValidRequest(sold = 3, previouslyRefunded = 2, requested = 1))
    }

    @Test fun exactAllocationNeverLosesRupiah() {
        val allocation = RefundRules.allocateTotal(10, listOf(1, 1, 1))
        assertEquals(listOf(4L, 3L, 3L), allocation)
        assertEquals(10L, allocation.sum())
    }

    @Test fun cumulativePartialRefundEndsAtExactLineNetTotal() {
        val first = RefundRules.incrementalRefundAmount(
            lineFinalTotal = 10_000,
            soldQuantity = 3,
            previouslyRefundedQuantity = 0,
            previouslyRefundedAmount = 0,
            requestedQuantity = 1
        )
        val remainder = RefundRules.incrementalRefundAmount(
            lineFinalTotal = 10_000,
            soldQuantity = 3,
            previouslyRefundedQuantity = 1,
            previouslyRefundedAmount = first,
            requestedQuantity = 2
        )
        assertEquals(3_333L, first)
        assertEquals(6_667L, remainder)
        assertEquals(10_000L, first + remainder)
    }

    @Test fun refundPaymentAllocationCannotExceedOriginalTender() {
        val original = linkedMapOf("CASH" to 60L, "QRIS" to 40L)
        val first = RefundRules.allocateRefundByRemainingCapacity(50, original, emptyMap())
        val second = RefundRules.allocateRefundByRemainingCapacity(50, original, first)
        assertEquals(100L, first.values.sum() + second.values.sum())
        assertEquals(60L, (first["CASH"] ?: 0L) + (second["CASH"] ?: 0L))
        assertEquals(40L, (first["QRIS"] ?: 0L) + (second["QRIS"] ?: 0L))
    }
}
