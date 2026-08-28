package com.trapezo.pos.domain.model

/** Shared pure validation for operational quantity inputs and refund selections. */
object OperationalInputRules {
    data class StockAdjustment(val amount: Long?, val error: String?)

    fun stockAdjustment(mode: String, rawAmount: String, reason: String): StockAdjustment {
        if (mode !in setOf("ADD", "REMOVE", "SET")) return StockAdjustment(null, "Mode adjustment tidak valid")
        val clean = rawAmount.trim()
        if (clean.isEmpty() || clean.any { !it.isDigit() }) {
            return StockAdjustment(null, "Jumlah stok harus berupa bilangan bulat non-negatif")
        }
        val amount = clean.toLongOrNull()
            ?: return StockAdjustment(null, "Jumlah stok terlalu besar")
        if (mode != "SET" && amount <= 0L) {
            return StockAdjustment(null, "Jumlah ADD/REMOVE harus lebih besar dari 0")
        }
        if (reason.isBlank()) return StockAdjustment(null, "Alasan wajib diisi")
        return StockAdjustment(amount, null)
    }

    fun validRefundSelection(
        selected: Map<Long, Boolean>,
        quantities: Map<Long, Long>,
        remainingQuantities: Map<Long, Long>
    ): Boolean {
        val selectedIds = selected.filterValues { it }.keys
        if (selectedIds.isEmpty()) return false
        return selectedIds.all { id ->
            val quantity = quantities[id] ?: 0L
            val remaining = remainingQuantities[id] ?: 0L
            quantity >= 1L && quantity <= remaining
        }
    }
}
