package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.PaymentAllocation
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentAllocationTest {
    @Test fun cashTenderAboveTotal_recordsOnlySaleValueAndReturnsChange() {
        val result = PaymentAllocation.settle(linkedMapOf("CASH" to 100_000L), grandTotal = 64_000L)
        assertEquals(100_000L, result.tendered)
        assertEquals(36_000L, result.change)
        assertEquals(linkedMapOf("CASH" to 64_000L), result.settled)
    }

    @Test fun mixedPayments_allocateSettlementAcrossMethodsInEntryOrder() {
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 20_000L, "CASH" to 50_000L), grandTotal = 60_000L)
        assertEquals(10_000L, result.change)
        assertEquals(linkedMapOf("QRIS" to 20_000L, "CASH" to 40_000L), result.settled)
    }
}
