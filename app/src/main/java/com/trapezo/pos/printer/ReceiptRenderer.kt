package com.trapezo.pos.printer

import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money

/**
 * Pure, context-free receipt formatting. Separated from ReceiptService so the ESC/POS
 * width-safety and encoding logic stays unit-testable on the JVM without a Context.
 */
object ReceiptRenderer {

    fun receiptText(
        store: ReceiptService.StoreReceiptInfo,
        sale: SaleEntity,
        items: List<SaleItemEntity>,
        payments: List<PaymentEntity>
    ): String = buildString {
        val width = if (store.paperMm == 58) 32 else 42
        fun center(value: String) {
            val clipped = value.take(width)
            append(clipped.padStart((width + clipped.length) / 2).padEnd(width)).append('\n')
        }
        fun rule() { append("-".repeat(width)).append('\n') }
        fun total(label: String, value: Long) {
            val right = Money.num(value)
            // Defensive width math: long labels or values must not produce negative
            // padding/take lengths (which would throw IllegalArgumentException).
            val keep = (width - right.length - 1).coerceAtLeast(0)
            append(label.take(keep))
                .append(" ".repeat((width - label.length - right.length).coerceAtLeast(1)))
                .append(right).append('\n')
        }

        center(store.name)
        if (store.showAddress && store.address.isNotBlank()) center(store.address)
        if (store.showPhone && store.phone.isNotBlank()) center(store.phone)
        rule()
        append("Invoice: ${sale.invoiceNumber}\n")
        append("Tanggal: ${Dates.dmyhm(sale.createdAt)}\n")
        append("Kasir  : ${sale.userNameSnapshot}\n")
        sale.customerNameSnapshot?.takeIf { it.isNotBlank() }?.let { append("Customer: $it\n") }
        rule()
        items.forEach { item ->
            append(item.productNameSnapshot.take(width)).append('\n')
            val left = "${item.quantity} x ${Money.num(item.unitPrice)}"
            val right = Money.num(item.subtotal)
            append(left).append(" ".repeat((width - left.length - right.length).coerceAtLeast(1))).append(right).append('\n')
        }
        rule()
        total("Subtotal", sale.subtotal)
        if (sale.discount > 0) total("Diskon", -sale.discount)
        if (sale.tax > 0) total("Pajak", sale.tax)
        if (sale.serviceCharge > 0) total("Service", sale.serviceCharge)
        total("TOTAL", sale.grandTotal)
        rule()
        payments.forEach { total(methodLabel(it.method), it.amount) }
        if (sale.changeAmount > 0) total("Kembalian", sale.changeAmount)
        rule()
        center(store.footer)
    }

    fun escPosBytes(store: ReceiptService.StoreReceiptInfo, sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): ByteArray {
        val init = byteArrayOf(0x1b, 0x40)
        val cut = byteArrayOf(0x1d, 0x56, 0x00)
        return init + receiptText(store, sale, items, payments).toEscPos() + byteArrayOf(0x0a, 0x0a, 0x0a) + cut
    }

    /**
     * Deliberate, explicit ESC/POS byte mapping. Printable characters in the extended
     * ASCII range are kept; structural control characters required by receipt layout
     * (LF 0x0A line feed, CR 0x0D) are preserved; other non-ASCII/control characters
     * (e.g. emoji, CJK) fall back to '?' rather than producing invalid multi-byte output.
     */
    private fun String.toEscPos(): ByteArray {
        val out = ByteArray(length)
        for (i in indices) {
            val c = this[i]
            out[i] = when {
                c.code == 0x0A -> 0x0A.toByte() // LF — receipt line structure
                c.code == 0x0D -> 0x0D.toByte() // CR — allowed control
                c.code in 0x20..0xFF -> c.code.toByte()
                else -> '?'.code.toByte()
            }
        }
        return out
    }

    private fun methodLabel(method: String): String = when (method) {
        "CASH" -> "Tunai"
        "CREDIT_CARD" -> "Kartu Kredit"
        "EWALLET" -> "E-Wallet"
        else -> method
    }
}
