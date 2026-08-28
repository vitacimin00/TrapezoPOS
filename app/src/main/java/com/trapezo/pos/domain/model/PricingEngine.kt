package com.trapezo.pos.domain.model

import java.math.BigInteger

data class PricingBreakdown(
    val totals: Totals,
    val lineDiscounts: List<Long>,
    val lineNetTotals: List<Long>
)

/** Exact integer pricing used by both the POS preview and persisted checkout snapshots. */
object PricingEngine {
    private fun percentOf(base: Long, percent: Long): Long {
        if (base <= 0 || percent <= 0) return 0L
        return BigInteger.valueOf(base)
            .multiply(BigInteger.valueOf(percent.coerceIn(0, 100)))
            .divide(BigInteger.valueOf(100))
            .longValueExact()
    }

    fun price(
        lines: List<CartLine>,
        discount: OrderDiscount,
        taxPercent: Long,
        servicePercent: Long,
        roundingStep: Long
    ): PricingBreakdown {
        if (lines.isEmpty()) return PricingBreakdown(Totals(), emptyList(), emptyList())

        val lineSubtotals = lines.map { it.subtotal.coerceAtLeast(0) }
        val subtotal = lineSubtotals.sum()
        val discountTotal = discount.amountFor(subtotal).coerceIn(0, subtotal)
        val lineDiscounts = RefundRules.allocateTotal(discountTotal, lineSubtotals)
        val afterDiscount = lineSubtotals.mapIndexed { index, amount ->
            (amount - lineDiscounts[index]).coerceAtLeast(0)
        }

        val serviceWeights = afterDiscount.mapIndexed { index, amount ->
            if (lines[index].nonServiceChargeItem) 0L else amount
        }
        val serviceTotal = percentOf(serviceWeights.sum(), servicePercent)
        val serviceAlloc = RefundRules.allocateTotal(serviceTotal, serviceWeights)

        val taxWeights = afterDiscount.mapIndexed { index, amount ->
            if (lines[index].taxFreeItem) 0L else amount
        }
        val taxTotal = percentOf(taxWeights.sum(), taxPercent)
        val taxAlloc = RefundRules.allocateTotal(taxTotal, taxWeights)

        val preRoundLineTotals = afterDiscount.indices.map { index ->
            afterDiscount[index] + serviceAlloc[index] + taxAlloc[index]
        }
        val unroundedGrand = preRoundLineTotals.sum()
        val step = roundingStep.coerceAtLeast(0)
        val grand = if (step > 1 && unroundedGrand > 0) {
            ((unroundedGrand + step / 2) / step) * step
        } else {
            unroundedGrand
        }
        val lineNetTotals = RefundRules.allocateTotal(grand.coerceAtLeast(0), preRoundLineTotals)

        return PricingBreakdown(
            totals = Totals(
                subtotal = subtotal,
                discount = discountTotal,
                tax = taxTotal,
                serviceCharge = serviceTotal,
                grandTotal = grand.coerceAtLeast(0),
                itemCount = lines.size
            ),
            lineDiscounts = lineDiscounts,
            lineNetTotals = lineNetTotals
        )
    }
}
