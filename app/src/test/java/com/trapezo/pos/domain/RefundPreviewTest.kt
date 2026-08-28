package com.trapezo.pos.domain

import com.trapezo.pos.domain.model.RefundPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundPreviewTest {

    private fun line(
        id: Long = 1,
        sold: Long = 3,
        alreadyQty: Long = 0,
        alreadyAmount: Long = 0,
        netTotal: Long = 30_000
    ) = RefundPreview.Line(
        saleItemId = id,
        productName = "Item $id",
        soldQuantity = sold,
        alreadyRefundedQuantity = alreadyQty,
        alreadyRefundedAmount = alreadyAmount,
        lineNetTotal = netTotal
    )

    @Test fun noPriorRefund_fullLineRemaining() {
        val l = line(sold = 3, netTotal = 30_000)
        assertEquals(3, l.remainingQuantity)
        assertEquals(30_000L, l.remainingRefundableAmount)
        assertTrue(!l.fullyRefunded)
    }

    @Test fun onePriorPartialRefund_remainingQuantityAndValue() {
        // Sold 3 net 30.000; refunded 1 qty for 10.000 already.
        val l = line(sold = 3, alreadyQty = 1, alreadyAmount = 10_000, netTotal = 30_000)
        assertEquals(2, l.remainingQuantity)
        assertEquals(20_000L, l.remainingRefundableAmount)
    }

    @Test fun partialRefundOfRemaining_matchesIncrementalRule() {
        val l = line(sold = 3, alreadyQty = 1, alreadyAmount = 10_000, netTotal = 30_000)
        // Refund 1 more: target = 30.000 * 2 / 3 = 20.000; minus already 10.000 -> 10.000
        assertEquals(10_000L, RefundPreview.lineRefundAmount(l, requestedQuantity = 1))
    }

    @Test fun finalPartialRefundConsumesExactRemainingNetValue() {
        // Sold 3 net 30.000; refunded 2 for 19.999 (odd split); final refund of 1 must give exactly 10.001.
        val l = line(sold = 3, alreadyQty = 2, alreadyAmount = 19_999, netTotal = 30_000)
        assertEquals(30_000L - 19_999L, RefundPreview.lineRefundAmount(l, requestedQuantity = 1))
    }

    @Test fun fullyRefundedLine_isUnavailableAndYieldsZero() {
        val l = line(sold = 2, alreadyQty = 2, alreadyAmount = 30_000, netTotal = 30_000)
        assertTrue(l.fullyRefunded)
        assertEquals(0, l.remainingQuantity)
        assertEquals(0L, RefundPreview.lineRefundAmount(l, requestedQuantity = 1))
    }

    @Test fun previewTotals_composeAcrossLines() {
        val lines = listOf(
            line(id = 1, sold = 3, alreadyQty = 1, alreadyAmount = 10_000, netTotal = 30_000),
            line(id = 2, sold = 2, netTotal = 20_000)
        )
        val preview = RefundPreview.preview(
            saleGrandTotal = 50_000,
            alreadyRefundedTotal = 10_000,
            lines = lines,
            requestedQuantities = mapOf(1L to 1L, 2L to 2L)
        )
        // Line1: 1 more of remaining 2 -> 10.000. Line2: full 2 -> 20.000. Total 30.000.
        assertEquals(30_000L, preview.currentRefundTotal)
        assertEquals(10_000L, preview.alreadyRefundedTotal)
        assertEquals(40_000L, preview.remainingSaleValue)
    }

    @Test fun finalFullRefund_matchesRepositorySemantics() {
        // Refunding every remaining unit must total the exact remaining sale value.
        val lines = listOf(
            line(id = 1, sold = 3, alreadyQty = 1, alreadyAmount = 9_999, netTotal = 30_000),
            line(id = 2, sold = 2, netTotal = 20_000)
        )
        val preview = RefundPreview.preview(
            saleGrandTotal = 50_000,
            alreadyRefundedTotal = 9_999,
            lines = lines,
            requestedQuantities = mapOf(1L to 2L, 2L to 2L)
        )
        // Full sale value 50.000 - already 9.999 = 40.001
        assertEquals(40_001L, preview.currentRefundTotal)
        assertEquals(preview.remainingSaleValue, preview.currentRefundTotal)
    }
}
