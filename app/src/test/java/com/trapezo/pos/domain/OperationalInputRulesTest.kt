package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.OperationalInputRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalInputRulesTest {
    @Test fun setZero_isValidIntegerStockAdjustment() {
        val result = OperationalInputRules.stockAdjustment("SET", "0", "Stock opname")
        assertEquals(0L, result.amount)
        assertNull(result.error)
    }

    @Test fun addAndRemove_requirePositiveInteger() {
        assertTrue(OperationalInputRules.stockAdjustment("ADD", "0", "x").error != null)
        assertTrue(OperationalInputRules.stockAdjustment("REMOVE", "0", "x").error != null)
        assertNull(OperationalInputRules.stockAdjustment("ADD", "2", "x").error)
        assertNull(OperationalInputRules.stockAdjustment("REMOVE", "2", "x").error)
    }

    @Test fun stockQuantity_rejectsMoneyFormattingAndNegativeOrBlank() {
        assertTrue(OperationalInputRules.stockAdjustment("SET", "1.000", "x").error != null)
        assertTrue(OperationalInputRules.stockAdjustment("SET", "-1", "x").error != null)
        assertTrue(OperationalInputRules.stockAdjustment("SET", "", "x").error != null)
    }

    @Test fun stockAdjustment_requiresReason() {
        assertTrue(OperationalInputRules.stockAdjustment("SET", "0", " ").error != null)
    }

    @Test fun everySelectedRefundLine_requiresValidQuantity() {
        assertTrue(OperationalInputRules.validRefundSelection(mapOf(1L to true), mapOf(1L to 2L), mapOf(1L to 3L)))
        assertFalse(OperationalInputRules.validRefundSelection(mapOf(1L to true, 2L to true), mapOf(1L to 2L, 2L to 0L), mapOf(1L to 3L, 2L to 3L)))
        assertFalse(OperationalInputRules.validRefundSelection(mapOf(1L to true), mapOf(1L to 4L), mapOf(1L to 3L)))
        assertFalse(OperationalInputRules.validRefundSelection(emptyMap(), emptyMap(), emptyMap()))
    }
}
