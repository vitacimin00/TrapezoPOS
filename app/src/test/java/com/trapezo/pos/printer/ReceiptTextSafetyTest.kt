package com.trapezo.pos.printer

import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTextSafetyTest {

    private fun sale(invoice: String = "INV-20260828-000001", grandTotal: Long = 1_234_567L, change: Long = 0L) =
        SaleEntity(
            id = 1, invoiceNumber = invoice, userId = 1, userNameSnapshot = "Kasir Name",
            subtotal = grandTotal, discount = 0, tax = 0, serviceCharge = 0,
            grandTotal = grandTotal, changeAmount = change
        )

    private fun store(name: String = "Toko Example", footer: String = "Terima kasih telah berbelanja!") =
        ReceiptService.StoreReceiptInfo(
            name = name, address = "Jl. Contoh No. 1", phone = "08123456", footer = footer, paperMm = 58
        )

    @Test fun normalReceipt_rendersCoreFields() {
        val text = ReceiptRenderer.receiptText(
            store(), sale(),
            listOf(SaleItemEntity(saleId = 1, productNameSnapshot = "Kopi", quantity = 1, unitPrice = 1_234_567L, subtotal = 1_234_567L)),
            listOf(PaymentEntity(saleId = 1, method = "CASH", amount = 1_234_567L))
        )
        assertTrue(text.contains("INV-20260828-000001"))
        assertTrue(text.contains("Kopi"))
    }

    @Test fun veryLongProductName_doesNotThrow() {
        val longName = "Produk Dengan Nama Yang Sangat Sangat Panjang Sekali Melebihi Lebar Kertas Receipt Thermal 58mm"
        val text = ReceiptRenderer.receiptText(
            store(), sale(),
            listOf(SaleItemEntity(saleId = 1, productNameSnapshot = longName, quantity = 1, unitPrice = 1000L, subtotal = 1000L)),
            listOf(PaymentEntity(saleId = 1, method = "CASH", amount = 1000L))
        )
        assertTrue(text.contains(longName.take(32)))
    }

    @Test fun hugeMoneyValue_doesNotThrowOnWidthMath() {
        val text = ReceiptRenderer.receiptText(
            store(), sale(grandTotal = Long.MAX_VALUE),
            listOf(SaleItemEntity(saleId = 1, productNameSnapshot = "X", quantity = 1, unitPrice = Long.MAX_VALUE, subtotal = Long.MAX_VALUE)),
            listOf(PaymentEntity(saleId = 1, method = "CASH", amount = Long.MAX_VALUE))
        )
        assertTrue(text.isNotBlank())
    }

    @Test fun longPaymentReferenceAndCashier_doNotThrow() {
        val text = ReceiptRenderer.receiptText(
            store(),
            sale(grandTotal = 1_000L, change = 500L),
            emptyList(),
            listOf(PaymentEntity(saleId = 1, method = "CASH", amount = 1_500L, referenceNumber = "123456789012345678901234567890"))
        )
        assertTrue(text.isNotBlank())
    }

    @Test fun escPosBytes_mapsNonAsciiWithoutThrowing() {
        val bytes = ReceiptRenderer.escPosBytes(
            store(name = "Toko Emoji \uD83D\uDE00", footer = "Kembalian"), sale(),
            emptyList(), listOf(PaymentEntity(saleId = 1, method = "CASH", amount = 1_234_567L))
        )
        assertTrue(bytes.isNotEmpty())
        assertEquals(0x1b, bytes[0].toInt() and 0xff)
    }
}
