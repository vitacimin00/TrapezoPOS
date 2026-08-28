package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.RefundEntity
import com.trapezo.pos.data.entity.RefundItemEntity
import com.trapezo.pos.data.entity.RefundPaymentEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.domain.model.RefundRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes refunds as immutable financial movements; original sale rows are never rewritten. */
class RefundRepository(private val db: AppDatabase) {
    data class RequestedItem(val saleItem: SaleItemEntity, val quantity: Long)
    sealed class Result {
        data class Success(val refundId: Long, val total: Long) : Result()
        data class Error(val message: String) : Result()
    }

    private data class PendingLine(val item: SaleItemEntity, val quantity: Long, val amount: Long)

    suspend fun refund(
        saleId: Long,
        userId: Long,
        request: List<RequestedItem>,
        reason: String
    ): Result = withContext(Dispatchers.IO) {
        if (request.isEmpty()) return@withContext Result.Error("Pilih minimal satu item untuk refund")
        if (reason.trim().isEmpty()) return@withContext Result.Error("Alasan refund wajib diisi")

        val requestedById = LinkedHashMap<Long, Long>()
        request.forEach { row ->
            val id = row.saleItem.id
            if (id <= 0L || row.quantity <= 0L) return@withContext Result.Error("Item atau quantity refund tidak valid")
            requestedById[id] = (requestedById[id] ?: 0L) + row.quantity
        }

        try {
            var refundId = 0L
            var total = 0L
            db.withTransaction {
                val actor = db.userDao().byId(userId)
                    ?: throw IllegalArgumentException("Akun tidak ditemukan")
                if (!actor.isActive || actor.role != "ADMIN") {
                    throw IllegalArgumentException("Refund hanya dapat diproses oleh admin aktif")
                }

                val sale = db.saleDao().saleById(saleId)
                    ?: throw IllegalArgumentException("Transaksi tidak ditemukan")
                if (sale.transactionStatus == "REFUNDED" || sale.transactionStatus == "VOID") {
                    throw IllegalArgumentException("Transaksi tidak dapat direfund")
                }

                val activeShift = db.shiftDao().openShiftForUser(userId)
                    ?: throw IllegalArgumentException("Admin harus membuka shift sebelum memproses refund")
                if (activeShift.status != "OPEN") throw IllegalArgumentException("Shift refund sudah ditutup")

                val saleItems = db.saleDao().itemsFor(saleId).associateBy { it.id }
                val pending = requestedById.map { (saleItemId, quantity) ->
                    val item = saleItems[saleItemId] ?: throw IllegalArgumentException("Item refund tidak valid")
                    val alreadyQty = db.refundDao().refundedQtyFor(item.id)
                    val alreadyAmount = db.refundDao().refundedAmountFor(item.id)
                    if (!RefundRules.isValidRequest(item.quantity, alreadyQty, quantity)) {
                        throw IllegalArgumentException("Refund ${item.productNameSnapshot} melebihi quantity transaksi")
                    }
                    PendingLine(
                        item = item,
                        quantity = quantity,
                        amount = RefundRules.incrementalRefundAmount(
                            lineFinalTotal = item.netTotal,
                            soldQuantity = item.quantity,
                            previouslyRefundedQuantity = alreadyQty,
                            previouslyRefundedAmount = alreadyAmount,
                            requestedQuantity = quantity
                        )
                    )
                }

                total = pending.sumOf { it.amount }
                val alreadyRefundedTotal = db.refundDao().refundedTotalFor(saleId)
                val remainingSaleValue = (sale.grandTotal - alreadyRefundedTotal).coerceAtLeast(0)
                if (total > remainingSaleValue) throw IllegalStateException("Nilai refund melebihi sisa nilai transaksi")

                val rawMethods = LinkedHashMap<String, Long>()
                db.saleDao().paymentsFor(saleId).forEach { payment ->
                    if (payment.amount > 0) rawMethods[payment.method] = (rawMethods[payment.method] ?: 0L) + payment.amount
                }
                if (rawMethods.isEmpty() && sale.grandTotal > 0) rawMethods["OTHER"] = sale.grandTotal

                val normalizedMethods = LinkedHashMap<String, Long>()
                if (rawMethods.isNotEmpty()) {
                    val normalized = RefundRules.allocateTotal(sale.grandTotal, rawMethods.values.toList())
                    rawMethods.keys.forEachIndexed { index, method -> normalizedMethods[method] = normalized[index] }
                }
                val alreadyByMethod = db.refundDao().refundedMethodTotalsFor(saleId).associate { it.method to it.total }
                val methodRefund = if (total > 0) {
                    RefundRules.allocateRefundByRemainingCapacity(total, normalizedMethods, alreadyByMethod)
                } else linkedMapOf()

                val cashRefund = methodRefund["CASH"] ?: 0L
                val nonCashRefund = total - cashRefund
                if (cashRefund > activeShift.expectedCash) {
                    throw IllegalArgumentException("Kas shift tidak cukup untuk refund tunai ini. Kas tersedia Rp ${activeShift.expectedCash}")
                }

                val now = System.currentTimeMillis()
                refundId = db.refundDao().insertRefund(
                    RefundEntity(
                        saleId = saleId,
                        userId = userId,
                        shiftId = activeShift.id,
                        total = total,
                        reason = reason.trim(),
                        createdAt = now
                    )
                )
                db.refundDao().insertRefundItems(
                    pending.map { row ->
                        RefundItemEntity(
                            refundId = refundId,
                            saleItemId = row.item.id,
                            productId = row.item.productId,
                            quantity = row.quantity,
                            amount = row.amount
                        )
                    }
                )
                if (methodRefund.isNotEmpty()) {
                    db.refundDao().insertRefundPayments(
                        methodRefund.map { (method, amount) ->
                            RefundPaymentEntity(refundId = refundId, method = method, amount = amount, createdAt = now)
                        }
                    )
                }

                pending.forEach { row ->
                    val productId = row.item.productId ?: return@forEach
                    val product = db.productDao().byId(productId) ?: return@forEach
                    if (product.trackInventory) {
                        db.productDao().applyDelta(productId, row.quantity)
                        db.inventoryDao().insert(
                            InventoryMovementEntity(
                                productId = productId,
                                type = "REFUND_IN",
                                quantity = row.quantity,
                                referenceId = refundId,
                                note = reason.trim(),
                                userId = userId,
                                createdAt = now
                            )
                        )
                    }
                }

                val nowFullyRefunded = db.saleDao().itemsFor(saleId).all { item ->
                    RefundRules.isLineFullyRefunded(
                        sold = item.quantity,
                        previouslyRefunded = db.refundDao().refundedQtyFor(item.id),
                        requested = 0L
                    )
                }
                db.saleDao().setStatus(saleId, if (nowFullyRefunded) "REFUNDED" else "PARTIALLY_REFUNDED")

                if (db.shiftDao().addRefundTotals(activeShift.id, cashRefund, nonCashRefund) != 1) {
                    throw IllegalStateException("Shift berubah saat refund diproses; coba lagi")
                }
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = userId,
                        action = "REFUND_CREATE",
                        referenceType = "refund",
                        referenceId = refundId,
                        description = "Refund sale ${sale.invoiceNumber}: Rp $total",
                        createdAt = now
                    )
                )
            }
            Result.Success(refundId, total)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Refund gagal")
        }
    }
}
