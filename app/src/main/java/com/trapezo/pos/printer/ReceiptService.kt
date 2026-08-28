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
import java.io.File

/**
 * Receipt renderer for 58/80mm thermal format. It supplies both printable
 * ESC/POS payloads and an honest, shareable PDF fallback.
 *
 * Pure formatting lives in [ReceiptRenderer] (context-free, unit-testable); this
 * class owns only the Context-dependent PDF/share paths.
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
    ): String = ReceiptRenderer.receiptText(store, sale, items, payments)

    fun escPosBytes(store: StoreReceiptInfo, sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): ByteArray =
        ReceiptRenderer.escPosBytes(store, sale, items, payments)

    fun createPdf(store: StoreReceiptInfo, sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): File {
        val width = if (store.paperMm == 58) 384 else 576
        val textLines = ReceiptRenderer.receiptText(store, sale, items, payments).lines()
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
}
