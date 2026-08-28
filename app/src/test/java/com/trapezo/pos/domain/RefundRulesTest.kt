package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.RefundRules
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

    @Test fun proratedRefundUsesSaleItemSubtotalNotListPrice() {
        assertTrue(RefundRules.refundAmount(lineSubtotal = 15_000, soldQuantity = 2, refundQuantity = 1) == 7_500L)
    }
}
