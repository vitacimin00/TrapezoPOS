package com.trapezo.pos.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"]),
        Index(value = ["sku"]),
        Index(value = ["name"]),
        Index(value = ["categoryId"])
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val alternativeName: String = "",
    val categoryId: Long? = null,
    val brand: String = "",
    val sku: String = "",
    val barcode: String = "",
    val buyPrice: Long = 0,
    val marketPrice: Long = 0,
    val sellPrice: Long = 0,
    val posSellPrice: Long = 0,
    val dynamicPriceEnabled: Boolean = false,
    val commission: Long = 0,
    val customerCommission: Long = 0,
    val customerCommissionPercentage: Boolean = false,
    val trackInventory: Boolean = true,
    val stockQty: Long = 0,
    val lowStockAlert: Long = 5,   // minimum-stock threshold
    val uom: String = "PCS",
    val uomName: String = "Pieces",
    val uomConverter: Double = 1.0,
    val uomBuyPrice: Long = 0,
    val uomSellPrice: Long = 0,
    val uomSellPricePos: Long = 0,
    val qtyFastMoving: Long = 0,
    val weightKg: Double = 0.0,
    val loyaltyPoints: Long = 0,
    val published: Boolean = true,
    val posHidden: Boolean = false,
    val description: String = "",
    val photo: String? = null,
    val notes: String = "",
    val taxFreeItem: Boolean = false,
    val nonServiceCharge: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "product_barcodes",
    indices = [Index(value = ["barcode"]), Index(value = ["productId"])],
    foreignKeys = [ForeignKey(
        entity = ProductEntity::class,
        parentColumns = ["id"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ProductBarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val barcode: String,
    val isPrimary: Boolean = false
)

@Entity(
    tableName = "inventory_movements",
    indices = [Index(value = ["productId"]), Index(value = ["createdAt"])],
    foreignKeys = [ForeignKey(
        entity = ProductEntity::class,
        parentColumns = ["id"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class InventoryMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: String,     // INITIAL | ADJUST_ADD | ADJUST_REMOVE | ADJUST_SET | SALE | REFUND_IN | IMPORT | EXPORT_FILE
    val quantity: Long,   // signed delta applied to stock
    val referenceId: Long? = null,
    val note: String = "",
    val userId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
