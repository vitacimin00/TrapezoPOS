package com.trapezo.pos.domain.model

/**
 * Separates tendered money from revenue settled against a sale.
 * Cash may overpay and generate change; non-cash may never exceed the remaining bill.
 * Settlement is deterministic: non-cash is settled first (in tender order), cash last,
 * so change can only be produced from physical cash tender.
 */
object PaymentAllocation {
    const val CASH = "CASH"

    data class Result(
        val settled: LinkedHashMap<String, Long>,
        val tendered: Long,
        val change: Long,
        val shortfall: Long
    )

    fun settle(tenders: Map<String, Long>, grandTotal: Long): Result {
        val total = grandTotal.coerceAtLeast(0)
        val settled = LinkedHashMap<String, Long>()
        val tendered = tenders.values.sumOf { it.coerceAtLeast(0) }
        var remaining = total

        // Non-cash first: each must fit within the remaining bill. Any non-cash
        // overpayment rejects the whole settlement (no manufactured change).
        for ((method, raw) in tenders) {
            if (method == CASH) continue
            val value = raw.coerceAtLeast(0)
            if (value > remaining) {
                return Result(
                    settled = LinkedHashMap(),
                    tendered = tendered,
                    change = 0,
                    shortfall = total
                )
            }
            if (value > 0) {
                settled[method] = value
                remaining -= value
            }
        }

        // Cash last: may exceed the remaining bill; the excess is physical change.
        val cashTender = tenders[CASH]?.coerceAtLeast(0) ?: 0L
        val cashSettled = minOf(cashTender, remaining)
        if (cashSettled > 0) settled[CASH] = cashSettled
        remaining -= cashSettled

        return Result(
            settled = settled,
            tendered = tendered,
            change = cashTender - cashSettled,
            shortfall = remaining
        )
    }
}
