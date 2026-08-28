package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.PaymentAllocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- Track D mandatory cases ----

    @Test fun cashExact_settlesFullTotalNoChange() {
        val result = PaymentAllocation.settle(linkedMapOf("CASH" to 100_000L), grandTotal = 100_000L)
        assertEquals(0L, result.change)
        assertEquals(0L, result.shortfall)
        assertEquals(linkedMapOf("CASH" to 100_000L), result.settled)
    }

    @Test fun cashOverpay_settlesTotalAndChangeIsExcessCash() {
        val result = PaymentAllocation.settle(linkedMapOf("CASH" to 120_000L), grandTotal = 100_000L)
        assertEquals(linkedMapOf("CASH" to 100_000L), result.settled)
        assertEquals(20_000L, result.change)
        assertEquals(0L, result.shortfall)
        assertEquals(120_000L, result.tendered)
    }

    @Test fun nonCashExact_settlesFullTotalNoChange() {
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 100_000L), grandTotal = 100_000L)
        assertEquals(0L, result.change)
        assertEquals(0L, result.shortfall)
        assertEquals(linkedMapOf("QRIS" to 100_000L), result.settled)
    }

    @Test fun qrisOverpay_isRejected_notManufacturedChange() {
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 110_000L), grandTotal = 100_000L)
        assertEquals(0L, result.change)
        assertTrue(result.settled.isEmpty())
        assertTrue(result.shortfall > 0)
    }

    @Test fun transferOverpay_isRejected() {
        val result = PaymentAllocation.settle(linkedMapOf("TRANSFER" to 150_000L), grandTotal = 100_000L)
        assertTrue(result.settled.isEmpty())
        assertEquals(0L, result.change)
    }

    @Test fun debitOverpay_isRejected() {
        val result = PaymentAllocation.settle(linkedMapOf("DEBIT" to 101_000L), grandTotal = 100_000L)
        assertTrue(result.settled.isEmpty())
        assertEquals(0L, result.change)
    }

    @Test fun mixedQrisAndCash_cashProducesChange() {
        // Total 100; QRIS 80 + Cash 30 -> settled QRIS 80, CASH 20, change 10
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 80_000L, "CASH" to 30_000L), grandTotal = 100_000L)
        assertEquals(linkedMapOf("QRIS" to 80_000L, "CASH" to 20_000L), result.settled)
        assertEquals(10_000L, result.change)
        assertEquals(0L, result.shortfall)
        assertEquals(110_000L, result.tendered)
    }

    @Test fun multipleNonCashExceedingBill_isRejected() {
        // Total 100; QRIS 70 + Debit 40 = 110 non-cash -> REJECT
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 70_000L, "DEBIT" to 40_000L), grandTotal = 100_000L)
        assertTrue(result.settled.isEmpty())
        assertEquals(0L, result.change)
        assertTrue(result.shortfall > 0)
    }

    @Test fun mixedShortfall_isRejected() {
        // Total 100; QRIS 40 + Cash 50 = 90 < 100 -> shortfall 10
        val result = PaymentAllocation.settle(linkedMapOf("QRIS" to 40_000L, "CASH" to 50_000L), grandTotal = 100_000L)
        assertEquals(10_000L, result.shortfall)
        assertEquals(0L, result.change)
    }

    @Test fun settledSumEqualsGrandTotal_whenPaid() {
        val result = PaymentAllocation.settle(
            linkedMapOf("QRIS" to 60_000L, "CASH" to 55_000L),
            grandTotal = 100_000L
        )
        assertEquals(0L, result.shortfall)
        assertEquals(100_000L, result.settled.values.sum())
    }

    @Test fun allocationIsIndependentFromInputMapOrdering() {
        // Same economics, different map ordering must produce same result.
        val a = PaymentAllocation.settle(linkedMapOf("QRIS" to 80_000L, "CASH" to 30_000L), 100_000L)
        val b = PaymentAllocation.settle(linkedMapOf("CASH" to 30_000L, "QRIS" to 80_000L), 100_000L)
        assertEquals(a.settled, b.settled)
        assertEquals(a.change, b.change)
        assertEquals(a.shortfall, b.shortfall)
    }

    @Test fun cashOnlyShortfall_reportsShortfall() {
        val result = PaymentAllocation.settle(linkedMapOf("CASH" to 60_000L), grandTotal = 100_000L)
        assertEquals(40_000L, result.shortfall)
        assertEquals(0L, result.change)
        assertEquals(linkedMapOf("CASH" to 60_000L), result.settled)
    }
}
