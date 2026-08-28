package com.trapezo.pos.domain.model

/** Immutable-sale refund arithmetic. */
object RefundRules {
    fun isValidRequest(sold: Long, previouslyRefunded: Long, requested: Long): Boolean =
        requested > 0 && previouslyRefunded >= 0 && previouslyRefunded + requested <= sold

    fun isLineFullyRefunded(sold: Long, previouslyRefunded: Long, requested: Long): Boolean =
        sold > 0 && previouslyRefunded + requested >= sold

    /** Uses the recorded sale-line subtotal, preserving discounts/price snapshots. */
    fun refundAmount(lineSubtotal: Long, soldQuantity: Long, refundQuantity: Long): Long =
        if (soldQuantity <= 0 || refundQuantity <= 0) 0L else lineSubtotal * refundQuantity / soldQuantity
}