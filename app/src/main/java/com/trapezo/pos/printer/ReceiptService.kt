package com.trapezo.pos.printer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import java.io.File

/**
 * Receipt renderer for 58/80mm thermal format. It supplies both printable
 * ESC/POS payloads and an honest, shareable PDF fallback.
 */
class ReceiptService(private val context: Context) {
    data class StoreReceiptInfo(
        val name: String,
        val address: String = "",
        val phone: String = "",
        val footer: String = "Terima kasih telah berbelanja!",
        val paperMm: Int = 80,
        val showLogo: Boolean = true,
        val showAddress: Boolean = true,
        val showPhone: Boolean = true,
        val logoPath: String? = null
    )

    fun receiptText(
        store: StoreReceiptInfo,
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
            append(label.take(width - right.length - 1))
                .append(" ".repeat((width - label.length - right.length).coerceAtLeast(1)))
                .append(right).append('\n')
        }

        // ESC/POS image rasterization is intentionally not assumed; logo is rendered in PDF.
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

    fun escPosBytes(store: StoreReceiptInfo, sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): ByteArray {
        val init = byteArrayOf(0x1b, 0x40)
        val cut = byteArrayOf(0x1d, 0x56, 0x00)
        return init + receiptText(store, sale, items, payments).toByteArray(Charsets.US_ASCII) + byteArrayOf(0x0a, 0x0a, 0x0a) + cut
    }

    fun createPdf(store: StoreReceiptInfo, sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): File {
        val width = if (store.paperMm == 58) 384 else 576
        val textLines = receiptText(store, sale, items, payments).lines()
        val hasLogo = store.showLogo && !store.logoPath.isNullOrBlank() && File(store.logoPath).exists()
        val logoHeight = if (hasLogo) 108 else 0
        val height = (textLines.size * 20 + 54 + logoHeight).coerceAtLeast(240)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
        val canvas = page.canvas
        var y = 22f
        if (hasLogo) {
            BitmapFactory.decodeFile(store.logoPath)?.let { bitmap ->
                val targetWidth = (width * 0.45f).toInt().coerceAtMost(bitmap.width)
                val targetHeight = (bitmap.height * targetWidth.toFloat() / bitmap.width).toInt().coerceAtMost(88)
                val left = (width - targetWidth) / 2
                canvas.drawBitmap(bitmap, null, android.graphics.Rect(left, 12, left + targetWidth, 12 + targetHeight), Paint(Paint.ANTI_ALIAS_FLAG))
                y += targetHeight + 12
            }
        }
        val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
        textLines.forEach { line ->
            canvas.drawText(line, 12f, y, paint)
            y += 18f
        }
        doc.finishPage(page)
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "${sale.invoiceNumber}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Bagikan struk"
            )
        )
    }

    private fun methodLabel(method: String): String = when (method) {
        "CASH" -> "Tunai"
        "CREDIT_CARD" -> "Kartu Kredit"
        "EWALLET" -> "E-Wallet"
        else -> method
    }
}
