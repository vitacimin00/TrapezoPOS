package com.trapezo.pos.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["invoiceNumber"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["transactionStatus", "createdAt"]),
        Index(value = ["customerId"]),
        Index(value = ["userId"]),
        Index(value = ["shiftId"])
    ]
)
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val customerId: Long? = null,
    val customerNameSnapshot: String? = null,
    val userId: Long,
    val userNameSnapshot: String = "",
    val shiftId: Long? = null,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val serviceCharge: Long,
    val grandTotal: Long,
    val paidAmount: Long = 0,
    val changeAmount: Long = 0,
    val paymentStatus: String = "PAID",      // PAID | UNPAID | PARTIAL
    val transactionStatus: String = "COMPLETED", // COMPLETED | PARTIALLY_REFUNDED | REFUNDED | VOID
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sale_items",
    indices = [Index(value = ["saleId"]), Index(value = ["productId"])],
    foreignKeys = [ForeignKey(
        entity = SaleEntity::class,
        parentColumns = ["id"],
        childColumns = ["saleId"],
        onDelete = ForeignKey.CASCADE
    )]
    // NOTE: productId intentionally has NO FK so history survives product deletion.
)
data class SaleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long? = null,
    val productNameSnapshot: String,
    val barcodeSnapshot: String = "",
    val quantity: Long,
    val unitPrice: Long,
    val discount: Long = 0,
    val subtotal: Long,
    @ColumnInfo(defaultValue = "0") val netTotal: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "payments",
    indices = [Index(value = ["saleId"]), Index(value = ["method"])],
    foreignKeys = [ForeignKey(
        entity = SaleEntity::class,
        parentColumns = ["id"],
        childColumns = ["saleId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val method: String, // CASH | QRIS | TRANSFER | DEBIT | CREDIT_CARD | EWALLET | OTHER
    val amount: Long,
    val referenceNumber: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "payment_methods", indices = [Index(value = ["name"], unique = true)])
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val isActive: Boolean = true
)

@Entity(
    tableName = "refunds",
    indices = [Index(value = ["saleId"]), Index(value = ["shiftId"])],
    foreignKeys = [ForeignKey(
        entity = SaleEntity::class,
        parentColumns = ["id"],
        childColumns = ["saleId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RefundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val userId: Long,
    val shiftId: Long? = null,
    val total: Long,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "refund_items",
    indices = [Index(value = ["refundId"]), Index(value = ["saleItemId"])],
    foreignKeys = [ForeignKey(
        entity = RefundEntity::class,
        parentColumns = ["id"],
        childColumns = ["refundId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RefundItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val refundId: Long,
    val saleItemId: Long,
    val productId: Long? = null,
    val quantity: Long,
    val amount: Long
)

@Entity(
    tableName = "refund_payments",
    indices = [Index(value = ["refundId"]), Index(value = ["method"])],
    foreignKeys = [ForeignKey(
        entity = RefundEntity::class,
        parentColumns = ["id"],
        childColumns = ["refundId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RefundPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val refundId: Long,
    val method: String,
    val amount: Long,
    val createdAt: Long = System.currentTimeMillis()
)
