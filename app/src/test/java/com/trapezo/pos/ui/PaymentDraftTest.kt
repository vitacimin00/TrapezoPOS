package com.trapezo.pos.ui

import com.trapezo.pos.domain.model.PaymentAllocation
import com.trapezo.pos.ui.screens.AddTenderOutcome
import com.trapezo.pos.ui.screens.PaymentDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure state tests for the payment sheet reducer. No Compose, no database — these
 * verify the stale-state rules live in [PaymentDraft], which delegates all settlement
 * math to the locked [PaymentAllocation].
 */
class PaymentDraftTest {

    private val total = 100_000L

    private fun add(draft: PaymentDraft, amount: String, reference: String = ""): PaymentDraft {
        var d = draft.changeAmount(amount)
        if (reference.isNotEmpty()) d = d.changeReference(reference)
        val outcome = d.addTender()
        require(outcome is AddTenderOutcome.Accepted) { "expected accepted, got $outcome" }
        return outcome.draft
    }

    @Test
    fun removingTender_alsoRemovesItsReference_andRestoresRemaining() {
        var d = PaymentDraft.start(total, "QRIS")
        d = add(d, "50000", "QR-123")
        assertEquals(50_000L, d.tenders["QRIS"])
        assertEquals("QR-123", d.references["QRIS"])
        assertEquals(50_000L, d.remaining)

        d = d.removeTender("QRIS")

        assertTrue("tender must be removed", d.tenders.isEmpty())
        assertTrue("reference must be removed with its tender", d.references.isEmpty())
        assertEquals("remaining must be restored", total, d.remaining)
        assertEquals("default amount must reflect the restored remaining", total.toString(), d.amount)
    }

    @Test
    fun switchingMethod_clearsTemporaryReference() {
        var d = PaymentDraft.start(total, "QRIS")
        d = d.changeReference("QR-123")

        d = d.selectMethod("TRANSFER")

        assertEquals("TRANSFER", d.methodId)
        assertEquals("QRIS reference must not carry onto TRANSFER", "", d.reference)
    }

    @Test
    fun reAddingMethod_withBlankReference_doesNotResurrectStaleReference() {
        var d = PaymentDraft.start(total, "QRIS")
        d = add(d, "50000", "QR-123")
        d = d.removeTender("QRIS")

        // Re-add the same method with no reference typed.
        d = add(d, "50000")

        assertEquals(50_000L, d.tenders["QRIS"])
        assertTrue(
            "stale QR-123 reference must not reappear after re-add",
            !d.references.containsKey("QRIS")
        )
    }

    @Test
    fun cashOverpay_producesChange() {
        var d = PaymentDraft.start(total, "CASH").selectMethod("CASH")
        d = add(d, "150000")

        assertEquals(100_000L, d.settled.settled[PaymentAllocation.CASH])
        assertEquals(50_000L, d.settled.change)
        assertEquals(0L, d.remaining)
    }

    @Test
    fun nonCashOverpay_isRejected() {
        val d = PaymentDraft.start(total, "QRIS").changeAmount("150000")
        val outcome = d.addTender()

        assertTrue("non-cash overpay must be rejected", outcome is AddTenderOutcome.Rejected)
    }

    @Test
    fun splitPayment_settlesExactlyToGrandTotal() {
        var d = PaymentDraft.start(total, "QRIS")
        d = add(d, "60000", "QR-1")
        d = d.selectMethod("CASH")
        d = add(d, "40000")

        assertEquals(0L, d.settled.shortfall)
        assertEquals(total, d.settled.settled.values.sum())
        assertEquals(100_000L, d.settled.tendered)
        assertEquals(0L, d.settled.change)
    }

    @Test
    fun accumulatedTenderWithinMethod_sumsAndClearsTemporaryReference() {
        var d = PaymentDraft.start(total, "QRIS")
        d = add(d, "30000", "QR-A")
        d = add(d, "20000", "QR-B")   // same method accumulates; reference overwritten
        assertEquals(50_000L, d.tenders["QRIS"])
        assertEquals("QR-B", d.references["QRIS"])
        assertEquals("", d.reference)   // temporary reference cleared after commit
    }
}
