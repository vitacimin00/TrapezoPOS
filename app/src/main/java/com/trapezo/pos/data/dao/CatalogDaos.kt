package com.trapezo.pos.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.ProductBarcodeEntity
import com.trapezo.pos.data.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    // ---- basic CRUD ----
    @Insert
    suspend fun insert(p: ProductEntity): Long

    @Update
    suspend fun update(p: ProductEntity)

    @Query("UPDATE products SET isActive=:active, updatedAt=:now WHERE id=:id")
    suspend fun setActive(id: Long, active: Boolean, now: Long = System.currentTimeMillis()): Int

    /** Soft delete: mark inactive. Hard delete left to maintenance. */
    @Query("UPDATE products SET isActive=0, updatedAt=:now WHERE id=:id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM products WHERE id=:id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT * FROM products WHERE id=:id")
    suspend fun byId(id: Long): ProductEntity?

    // ---- listing & paging ----
    @Query(
        """SELECT * FROM products
           WHERE (:includeInactive = 1 OR isActive = 1)
             AND (:categoryId IS NULL OR categoryId = :categoryId)
             AND (:query = '' OR name LIKE '%'||:query||'%' COLLATE NOCASE
                             OR alternativeName LIKE '%'||:query||'%' COLLATE NOCASE
                             OR sku LIKE '%'||:query||'%' COLLATE NOCASE
                             OR barcode LIKE '%'||:query||'%')
           ORDER BY CASE WHEN :sort = 'name_asc' THEN name END ASC,
                    CASE WHEN :sort = 'name_desc' THEN name END DESC,
                    CASE WHEN :sort = 'price_asc' THEN sellPrice END ASC,
                    CASE WHEN :sort = 'price_desc' THEN sellPrice END DESC,
                    CASE WHEN :sort = 'stock_asc' THEN stockQty END ASC,
                    CASE WHEN :sort = 'stock_desc' THEN stockQty END DESC,
                    name ASC
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun page(query: String, categoryId: Long?, includeInactive: Boolean, sort: String, limit: Int, offset: Int): List<ProductEntity>

    @Query("SELECT * FROM products WHERE isActive=1 AND posHidden=0 ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun posPage(limit: Int, offset: Int): List<ProductEntity>

    @Query(
        """SELECT * FROM products WHERE isActive=1 AND posHidden=0
           AND (:q = '' OR name LIKE '%'||:q||'%' COLLATE NOCASE OR sku LIKE '%'||:q||'%' COLLATE NOCASE OR barcode LIKE '%'||:q||'%')
           ORDER BY name LIMIT :limit OFFSET :offset"""
    )
    suspend fun posSearch(q: String, limit: Int, offset: Int): List<ProductEntity>

    @Query(
        """SELECT COUNT(*) FROM products
           WHERE (:includeInactive = 1 OR isActive = 1)
             AND (:categoryId IS NULL OR categoryId = :categoryId)
             AND (:query = '' OR name LIKE '%'||:query||'%' COLLATE NOCASE OR sku LIKE '%'||:query||'%' COLLATE NOCASE OR barcode LIKE '%'||:query||'%')"""
    )
    suspend fun countPage(query: String, categoryId: Long?, includeInactive: Boolean): Int

    // ---- unified inventory-aware listing (Track D) ----
    // stockState: ALL = no stock filter; LOW = trackInventory=1 AND stockQty>0 AND stockQty<=lowStockAlert; OUT = trackInventory=1 AND stockQty<=0
    @Query(
        """SELECT * FROM products
           WHERE (:lifecycle = 'ALL' OR (:lifecycle = 'ACTIVE' AND isActive = 1) OR (:lifecycle = 'INACTIVE' AND isActive = 0))
             AND (:trackedOnly = 0 OR trackInventory = 1)
             AND (:categoryId IS NULL OR categoryId = :categoryId)
             AND (:stockState = 'ALL' OR
                  (:stockState = 'LOW' AND trackInventory = 1 AND stockQty > 0 AND stockQty <= lowStockAlert) OR
                  (:stockState = 'OUT' AND trackInventory = 1 AND stockQty <= 0))
             AND (:query = '' OR name LIKE '%'||:query||'%' COLLATE NOCASE
                             OR alternativeName LIKE '%'||:query||'%' COLLATE NOCASE
                             OR sku LIKE '%'||:query||'%' COLLATE NOCASE
                             OR barcode LIKE '%'||:query||'%')
           ORDER BY CASE WHEN :sort = 'name_asc' THEN name END ASC,
                    CASE WHEN :sort = 'name_desc' THEN name END DESC,
                    CASE WHEN :sort = 'price_asc' THEN sellPrice END ASC,
                    CASE WHEN :sort = 'price_desc' THEN sellPrice END DESC,
                    CASE WHEN :sort = 'stock_asc' THEN stockQty END ASC,
                    CASE WHEN :sort = 'stock_desc' THEN stockQty END DESC,
                    name ASC
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun filteredPage(
        query: String, categoryId: Long?, lifecycle: String, trackedOnly: Boolean,
        stockState: String, sort: String, limit: Int, offset: Int
    ): List<ProductEntity>

    @Query(
        """SELECT COUNT(*) FROM products
           WHERE (:lifecycle = 'ALL' OR (:lifecycle = 'ACTIVE' AND isActive = 1) OR (:lifecycle = 'INACTIVE' AND isActive = 0))
             AND (:trackedOnly = 0 OR trackInventory = 1)
             AND (:categoryId IS NULL OR categoryId = :categoryId)
             AND (:stockState = 'ALL' OR
                  (:stockState = 'LOW' AND trackInventory = 1 AND stockQty > 0 AND stockQty <= lowStockAlert) OR
                  (:stockState = 'OUT' AND trackInventory = 1 AND stockQty <= 0))
             AND (:query = '' OR name LIKE '%'||:query||'%' COLLATE NOCASE
                             OR alternativeName LIKE '%'||:query||'%' COLLATE NOCASE
                             OR sku LIKE '%'||:query||'%' COLLATE NOCASE
                             OR barcode LIKE '%'||:query||'%')"""
    )
    suspend fun countFiltered(
        query: String, categoryId: Long?, lifecycle: String, trackedOnly: Boolean, stockState: String
    ): Int

    // ---- barcode / sku lookups (fast paths, indexed columns) ----
    @Query("SELECT * FROM products WHERE barcode=:barcode AND isActive=1 LIMIT 1")
    suspend fun byBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE sku=:sku AND isActive=1 LIMIT 1")
    suspend fun bySku(sku: String): ProductEntity?

    // ---- duplicate checks ----
    @Query("SELECT COUNT(*) FROM products WHERE barcode<>'' AND barcode=:barcode AND id<>:excludeId")
    suspend fun barcodeTaken(barcode: String, excludeId: Long): Int

    @Query("SELECT COUNT(*) FROM products WHERE sku<>'' AND sku=:sku AND id<>:excludeId")
    suspend fun skuTaken(sku: String, excludeId: Long): Int

    // ---- inventory views ----
    @Query("SELECT * FROM products WHERE trackInventory=1 AND isActive=1 AND stockQty>0 AND stockQty<=lowStockAlert ORDER BY stockQty ASC")
    suspend fun lowStock(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE trackInventory=1 AND isActive=1 AND stockQty<=0 ORDER BY name ASC")
    suspend fun outOfStock(): List<ProductEntity>

    @Query(
        """UPDATE products SET stockQty = MAX(0, stockQty + :delta), updatedAt=:now WHERE id=:id"""
    )
    suspend fun applyDelta(id: Long, delta: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE products SET stockQty=:newQty, updatedAt=:now WHERE id=:id")
    suspend fun setQty(id: Long, newQty: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT stockQty FROM products WHERE id=:id")
    suspend fun stockOf(id: Long): Long?

    @Query("SELECT COUNT(*) FROM products WHERE isActive=1")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM products WHERE isActive=1")
    fun countActiveFlow(): Flow<Int>

    // ---- multi-barcode table ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBarcode(b: ProductBarcodeEntity)

}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(c: CategoryEntity): Long

    @Update
    suspend fun update(c: CategoryEntity)

    @Query("UPDATE categories SET isActive=:active, updatedAt=:now WHERE id=:id")
    suspend fun setActive(id: Long, active: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM categories WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM categories ORDER BY isActive DESC, name ASC")
    fun allFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name=:name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM products WHERE categoryId=:categoryId")
    suspend fun productsUsing(categoryId: Long): Int

    @Query("SELECT * FROM categories WHERE id=:id")
    suspend fun byId(id: Long): CategoryEntity?
}

@Dao
interface InventoryDao {
    @Insert
    suspend fun insert(m: InventoryMovementEntity): Long

    @Query("SELECT * FROM inventory_movements WHERE productId=:productId ORDER BY createdAt DESC LIMIT 200")
    suspend fun forProduct(productId: Long): List<InventoryMovementEntity>

    @Query("SELECT * FROM inventory_movements ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun recent(limit: Int, offset: Int): List<InventoryMovementEntity>

    /** Movement rows joined with product names in a single query (no N+1). */
    @Query(
        """SELECT m.id AS id, m.productId AS productId, m.type AS type, m.quantity AS quantity,
                  m.referenceId AS referenceId, m.note AS note, m.userId AS userId, m.createdAt AS createdAt,
                  p.name AS productName
           FROM inventory_movements m
           LEFT JOIN products p ON p.id = m.productId
           ORDER BY m.id DESC
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun recentWithProductName(limit: Int, offset: Int): List<MovementWithProduct>
}

/** Projection for movement history with the current product name resolved by SQL join. */
data class MovementWithProduct(
    val id: Long,
    val productId: Long,
    val type: String,
    val quantity: Long,
    val referenceId: Long?,
    val note: String,
    val userId: Long?,
    val createdAt: Long,
    val productName: String?
)

@Dao
interface PaymentMethodDao {
    @Query("SELECT * FROM payment_methods ORDER BY id ASC")
    suspend fun all(): List<com.trapezo.pos.data.entity.PaymentMethodEntity>

    @Query("SELECT * FROM payment_methods WHERE isActive=1 ORDER BY id ASC")
    suspend fun active(): List<com.trapezo.pos.data.entity.PaymentMethodEntity>

    @Query("SELECT * FROM payment_methods WHERE type=:type LIMIT 1")
    suspend fun byType(type: String): com.trapezo.pos.data.entity.PaymentMethodEntity?

    @Query("UPDATE payment_methods SET isActive=:active WHERE id=:id")
    suspend fun setActive(id: Long, active: Boolean)
}

@Dao
interface CustomerDao {
    @Insert
    suspend fun insert(c: com.trapezo.pos.data.entity.CustomerEntity): Long

    @Update
    suspend fun update(c: com.trapezo.pos.data.entity.CustomerEntity)

    @Query("DELETE FROM customers WHERE id=:id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM customers WHERE (:q='' OR name LIKE '%'||:q||'%' COLLATE NOCASE OR phone LIKE '%'||:q||'%' OR code LIKE '%'||:q||'%') ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun page(q: String, limit: Int, offset: Int): List<com.trapezo.pos.data.entity.CustomerEntity>

    @Query("SELECT COUNT(*) FROM customers WHERE (:q='' OR name LIKE '%'||:q||'%' COLLATE NOCASE OR phone LIKE '%'||:q||'%' OR code LIKE '%'||:q||'%')")
    suspend fun count(q: String): Int

    @Query("SELECT * FROM customers WHERE id=:id")
    suspend fun byId(id: Long): com.trapezo.pos.data.entity.CustomerEntity?

    @Query("SELECT * FROM customers WHERE code=:code LIMIT 1")
    suspend fun byCode(code: String): com.trapezo.pos.data.entity.CustomerEntity?

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun totalCount(): Int
}
