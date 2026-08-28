package com.trapezo.pos.domain.model

import java.math.BigInteger

/** Immutable-sale refund arithmetic and exact proportional allocation helpers. */
object RefundRules {
    fun isValidRequest(sold: Long, previouslyRefunded: Long, requested: Long): Boolean =
        requested > 0 && sold > 0 && previouslyRefunded >= 0 && previouslyRefunded + requested <= sold

    fun isLineFullyRefunded(sold: Long, previouslyRefunded: Long, requested: Long): Boolean =
        sold > 0 && previouslyRefunded >= 0 && requested >= 0 && previouslyRefunded + requested >= sold

    /**
     * Splits a non-negative integer total across non-negative weights with no lost Rupiah.
     * Largest fractional remainders receive the remaining units; index order breaks ties.
     */
    fun allocateTotal(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0) { "Total allocation tidak boleh negatif" }
        if (weights.isEmpty()) return emptyList()
        val clean = weights.map { it.coerceAtLeast(0) }
        val weightSum = clean.fold(BigInteger.ZERO) { acc, w -> acc + BigInteger.valueOf(w) }
        if (total == 0L || weightSum == BigInteger.ZERO) return List(weights.size) { 0L }

        val totalBi = BigInteger.valueOf(total)
        val bases = LongArray(clean.size)
        val remainders = Array(clean.size) { BigInteger.ZERO }
        var allocated = 0L
        clean.forEachIndexed { index, weight ->
            val numerator = totalBi * BigInteger.valueOf(weight)
            val parts = numerator.divideAndRemainder(weightSum)
            val base = parts[0].longValueExact()
            bases[index] = base
            remainders[index] = parts[1]
            allocated += base
        }

        var leftover = total - allocated
        val order = clean.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        var cursor = 0
        while (leftover > 0) {
            val targetIndex = order[cursor]
            bases[targetIndex] = bases[targetIndex] + 1L
            leftover--
            cursor++
            if (cursor == order.size) cursor = 0
        }
        return bases.toList()
    }

    /**
     * Returns only the amount for this request. Cumulative targeting guarantees that
     * several partial refunds add up exactly to the stored final line total.
     */
    fun incrementalRefundAmount(
        lineFinalTotal: Long,
        soldQuantity: Long,
        previouslyRefundedQuantity: Long,
        previouslyRefundedAmount: Long,
        requestedQuantity: Long
    ): Long {
        if (!isValidRequest(soldQuantity, previouslyRefundedQuantity, requestedQuantity)) return 0L
        val finalTotal = lineFinalTotal.coerceAtLeast(0)
        val alreadyAmount = previouslyRefundedAmount.coerceAtLeast(0)
        val newQty = previouslyRefundedQuantity + requestedQuantity
        val target = if (newQty == soldQuantity) {
            finalTotal
        } else {
            BigInteger.valueOf(finalTotal)
                .multiply(BigInteger.valueOf(newQty))
                .divide(BigInteger.valueOf(soldQuantity))
                .longValueExact()
        }
        return (target - alreadyAmount).coerceAtLeast(0)
    }

    /** Allocates a refund only across the unrefunded capacity of original tenders. */
    fun allocateRefundByRemainingCapacity(
        requestedTotal: Long,
        originalByMethod: LinkedHashMap<String, Long>,
        alreadyRefundedByMethod: Map<String, Long>
    ): LinkedHashMap<String, Long> {
        require(requestedTotal >= 0) { "Refund tidak boleh negatif" }
        val methods = originalByMethod.keys.toList()
        val capacities = methods.map { method ->
            (originalByMethod.getValue(method).coerceAtLeast(0) -
                (alreadyRefundedByMethod[method] ?: 0L).coerceAtLeast(0)).coerceAtLeast(0)
        }
        require(capacities.sum() >= requestedTotal) { "Refund melebihi sisa pembayaran transaksi" }
        val allocation = allocateTotal(requestedTotal, capacities)
        return LinkedHashMap<String, Long>().apply {
            methods.forEachIndexed { index, method ->
                if (allocation[index] > 0) put(method, allocation[index])
            }
        }
    }
}
