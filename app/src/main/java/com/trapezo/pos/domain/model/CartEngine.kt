package com.trapezo.pos.domain.model

/**
 * Deterministic, UI-independent cart rules.
 * A tracked item cannot exceed its snapshot stock quantity. A quantity of zero
 * removes a line. Product additions merge onto an existing line to keep a
 * scanner sequence fast and predictable.
 */
object CartEngine {

    data class Result(
        val lines: List<CartLine>,
        val accepted: Boolean,
        val message: String? = null
    )

    fun add(lines: List<CartLine>, incoming: CartLine): Result {
        val existing = lines.firstOrNull { it.productId == incoming.productId }
        return if (existing == null) {
            if (incoming.quantity <= 0) Result(lines, false, "Jumlah harus lebih besar dari 0")
            else if (incoming.trackInventory && incoming.quantity > incoming.stockQty) {
                Result(lines, false, "Stok ${incoming.name} tidak mencukupi")
            } else Result(lines + incoming.copy(), true)
        } else {
            setQuantity(lines, incoming.productId, existing.quantity + incoming.quantity)
        }
    }

    fun setQuantity(lines: List<CartLine>, productId: Long, newQuantity: Long): Result {
        val target = lines.firstOrNull { it.productId == productId }
            ?: return Result(lines, false, "Produk tidak ada di keranjang")
        if (newQuantity < 0) return Result(lines, false, "Jumlah tidak boleh negatif")
        if (newQuantity == 0L) return Result(lines.filterNot { it.productId == productId }, true)
        if (target.trackInventory && newQuantity > target.stockQty) {
            return Result(lines, false, "Stok ${target.name} tersisa ${target.stockQty}")
        }
        return Result(lines.map { if (it.productId == productId) it.copy(quantity = newQuantity) else it }, true)
    }

    fun remove(lines: List<CartLine>, productId: Long): Result =
        Result(lines.filterNot { it.productId == productId }, true)
}
