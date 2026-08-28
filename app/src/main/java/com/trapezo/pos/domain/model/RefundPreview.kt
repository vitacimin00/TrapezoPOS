package com.trapezo.pos.domain.model

/**
 * Pure refund preview computation for the refund dialog.
 * Reuses the locked RefundRules incremental arithmetic so the preview shown to the
 * admin is guaranteed compatible with the amount the repository will commit.
 */
object RefundPreview {

    data class Line(
        val saleItemId: Long,
        val productName: String,
        val soldQuantity: Long,
        val alreadyRefundedQuantity: Long,
        val alreadyRefundedAmount: Long,
        val lineNetTotal: Long
    ) {
        val remainingQuantity: Long get() = (soldQuantity - alreadyRefundedQuantity).coerceAtLeast(0)
        val fullyRefunded: Boolean get() = remainingQuantity <= 0
        val remainingRefundableAmount: Long get() = (lineNetTotal - alreadyRefundedAmount).coerceAtLeast(0)
    }

    data class Preview(
        val saleGrandTotal: Long,
        val alreadyRefundedTotal: Long,
        val remainingSaleValue: Long,
        val currentRefundTotal: Long,
        val lines: List<Line>
    )

    /**
     * Computes the exact refund amount for the requested quantities using the same
     * incremental rule the repository applies at commit time.
     */
    fun lineRefundAmount(line: Line, requestedQuantity: Long): Long =
        RefundRules.incrementalRefundAmount(
            lineFinalTotal = line.lineNetTotal,
            soldQuantity = line.soldQuantity,
            previouslyRefundedQuantity = line.alreadyRefundedQuantity,
            previouslyRefundedAmount = line.alreadyRefundedAmount,
            requestedQuantity = requestedQuantity
        )

    fun preview(
        saleGrandTotal: Long,
        alreadyRefundedTotal: Long,
        lines: List<Line>,
        requestedQuantities: Map<Long, Long>
    ): Preview {
        val current = lines.sumOf { line ->
            lineRefundAmount(line, requestedQuantities[line.saleItemId] ?: 0L)
        }
        return Preview(
            saleGrandTotal = saleGrandTotal,
            alreadyRefundedTotal = alreadyRefundedTotal,
            remainingSaleValue = (saleGrandTotal - alreadyRefundedTotal).coerceAtLeast(0),
            currentRefundTotal = current,
            lines = lines
        )
    }
}
