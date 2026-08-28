package com.trapezo.pos.domain.model

/**
 * A line inside the POS cart. Prices are snapshot copies of the product data
 * at the moment the item was added, so later product edits never affect an
 * in-progress cart (and historical receipts rely on their own snapshots).
 */
data class CartLine(
    val productId: Long,
    val name: String,
    val barcode: String?,
    val unitPrice: Long,
    var quantity: Long,
    val trackInventory: Boolean,
    val stockQty: Long,
    val taxFreeItem: Boolean,
    val nonServiceChargeItem: Boolean
) {
    val subtotal: Long get() = unitPrice * quantity
}

enum class DiscountKind { NONE, NOMINAL, PERCENT }

data class OrderDiscount(
    val kind: DiscountKind = DiscountKind.NONE,
    val value: Long = 0 // rupiah when NOMINAL, whole percent when PERCENT
) {
    fun amountFor(subtotal: Long): Long = when (kind) {
        DiscountKind.NONE -> 0L
        DiscountKind.NOMINAL -> minOf(value, subtotal)
        DiscountKind.PERCENT -> (subtotal * value.coerceIn(0, 100)) / 100
    }
}

data class Totals(
    val subtotal: Long = 0,
    val discount: Long = 0,
    val tax: Long = 0,
    val serviceCharge: Long = 0,
    val grandTotal: Long = 0,
    val itemCount: Int = 0
)

sealed class PayMethod(val id: String, val label: String) {
    object Cash : PayMethod("CASH", "Tunai")
    object Qris : PayMethod("QRIS", "QRIS")
    object Transfer : PayMethod("TRANSFER", "Transfer")
    object Debit : PayMethod("DEBIT", "Debit")
    object CreditCard : PayMethod("CREDIT_CARD", "Kartu Kredit")
    object EWallet : PayMethod("EWALLET", "E-Wallet")
    object Other : PayMethod("OTHER", "Lainnya")

    companion object {
        val ALL = listOf(Cash, Qris, Transfer, Debit, CreditCard, EWallet, Other)
        fun byId(id: String): PayMethod = ALL.firstOrNull { it.id == id } ?: Other
    }
}
