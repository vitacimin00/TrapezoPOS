package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.RefundEntity
import com.trapezo.pos.data.entity.RefundItemEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.domain.model.RefundRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes refunds as immutable new records; never rewrites the original sale or its items. */
class RefundRepository(private val db: AppDatabase) {
    data class RequestedItem(val saleItem: SaleItemEntity, val quantity: Long)
    sealed class Result { data class Success(val refundId: Long, val total: Long): Result(); data class Error(val message: String): Result() }

    suspend fun refund(saleId: Long, userId: Long, request: List<RequestedItem>, reason: String): Result = withContext(Dispatchers.IO) {
        if (request.isEmpty()) return@withContext Result.Error("Pilih minimal satu item untuk refund")
        if (reason.trim().isEmpty()) return@withContext Result.Error("Alasan refund wajib diisi")
        try {
            var refundId = 0L
            var total = 0L
            db.withTransaction {
                val sale = db.saleDao().saleById(saleId) ?: throw IllegalArgumentException("Transaksi tidak ditemukan")
                if (sale.transactionStatus == "REFUNDED" || sale.transactionStatus == "VOID") throw IllegalArgumentException("Transaksi tidak dapat direfund")
                val saleItems = db.saleDao().itemsFor(saleId).associateBy { it.id }
                request.forEach { r ->
                    val item = saleItems[r.saleItem.id] ?: throw IllegalArgumentException("Item refund tidak valid")
                    val already = db.refundDao().refundedQtyFor(item.id)
                    if (!RefundRules.isValidRequest(item.quantity, already, r.quantity)) {
                        throw IllegalArgumentException("Refund ${item.productNameSnapshot} melebihi quantity transaksi")
                    }
                }
                // Refund uses immutable sale-item subtotal snapshots so original discounts remain honored.
                total = request.sumOf { r ->
                    RefundRules.refundAmount(r.saleItem.subtotal, r.saleItem.quantity, r.quantity)
                }
                refundId = db.refundDao().insertRefund(
                    RefundEntity(saleId = saleId, userId = userId, total = total, reason = reason.trim())
                )
                db.refundDao().insertRefundItems(request.map { r ->
                    RefundItemEntity(
                        refundId = refundId,
                        saleItemId = r.saleItem.id,
                        productId = r.saleItem.productId,
                        quantity = r.quantity,
                        amount = RefundRules.refundAmount(r.saleItem.subtotal, r.saleItem.quantity, r.quantity)
                    )
                })
                for (r in request) {
                    val id = r.saleItem.productId ?: continue
                    val product = db.productDao().byId(id) ?: continue
                    if (product.trackInventory) {
                        db.productDao().applyDelta(id, r.quantity)
                        db.inventoryDao().insert(InventoryMovementEntity(productId = id, type = "REFUND_IN", quantity = r.quantity, referenceId = refundId, note = reason.trim(), userId = userId))
                    }
                }
                // The refund rows have already been inserted, so compare final persisted quantities directly.
                val nowFullyRefunded = db.saleDao().itemsFor(saleId).all { item ->
                    RefundRules.isLineFullyRefunded(
                        sold = item.quantity,
                        previouslyRefunded = db.refundDao().refundedQtyFor(item.id),
                        requested = 0L
                    )
                }
                db.saleDao().setStatus(saleId, if (nowFullyRefunded) "REFUNDED" else "PARTIALLY_REFUNDED")
                db.settingsDao().insertAudit(AuditLogEntity(userId = userId, action = "REFUND_CREATE", referenceType = "refund", referenceId = refundId, description = "Refund sale ${sale.invoiceNumber}: Rp $total"))
            }
            Result.Success(refundId, total)
        } catch (e: Exception) { Result.Error(e.message ?: "Refund gagal") }
    }
}
