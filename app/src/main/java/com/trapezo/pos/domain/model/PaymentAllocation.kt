package com.trapezo.pos.domain.model

/**
 * Separates tendered money from revenue settled against a sale. This prevents a
 * cash tender (e.g. Rp100.000 for a Rp64.000 bill) being written as Rp100.000
 * revenue; only Rp64.000 is allocated to payment and Rp36.000 is change.
 */
object PaymentAllocation {
    data class Result(
        val settled: LinkedHashMap<String, Long>,
        val tendered: Long,
        val change: Long,
        val shortfall: Long
    )

    fun settle(tenders: Map<String, Long>, grandTotal: Long): Result {
        val normalized = LinkedHashMap<String, Long>()
        var remaining = grandTotal.coerceAtLeast(0)
        var tendered = 0L
        for ((method, raw) in tenders) {
            val value = raw.coerceAtLeast(0)
            tendered += value
            val applied = minOf(value, remaining)
            if (applied > 0) normalized[method] = applied
            remaining -= applied
        }
        return Result(
            settled = normalized,
            tendered = tendered,
            change = (tendered - grandTotal).coerceAtLeast(0),
            shortfall = remaining
        )
    }
}
