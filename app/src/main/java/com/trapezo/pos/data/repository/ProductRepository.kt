package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.dao.CategoryDao
import com.trapezo.pos.data.dao.InventoryDao
import com.trapezo.pos.data.dao.ProductDao
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.ProductEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProductRepository(
    private val db: AppDatabase,
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val inventoryDao: InventoryDao,
    private val settings: SettingsRepository
) {
    suspend fun page(query: String, categoryId: Long?, includeInactive: Boolean, sort: String, page: Int, pageSize: Int = 50) =
        withContext(Dispatchers.IO) {
            Pair(
                productDao.page(query.trim(), categoryId, includeInactive, sort, pageSize, page * pageSize),
                productDao.countPage(query.trim(), categoryId, includeInactive)
            )
        }

    /** Unified listing with composable stock-state filter (Track D). */
    suspend fun filteredPage(
        query: String, categoryId: Long?, lifecycle: String, trackedOnly: Boolean,
        stockState: String, sort: String, page: Int, pageSize: Int = 50
    ): Pair<List<ProductEntity>, Int> = withContext(Dispatchers.IO) {
        Pair(
            productDao.filteredPage(query.trim(), categoryId, lifecycle, trackedOnly, stockState, sort, pageSize, page * pageSize),
            productDao.countFiltered(query.trim(), categoryId, lifecycle, trackedOnly, stockState)
        )
    }

    suspend fun posSearch(q: String, limit: Int = 20, offset: Int = 0): List<ProductEntity> =
        withContext(Dispatchers.IO) { productDao.posSearch(q.trim(), limit, offset) }

    suspend fun byId(id: Long): ProductEntity? = withContext(Dispatchers.IO) { productDao.byId(id) }

    suspend fun byBarcode(code: String): ProductEntity? = withContext(Dispatchers.IO) {
        val clean = code.trim()
        if (clean.isEmpty()) return@withContext null
        productDao.byBarcode(clean) ?: productDao.bySku(clean)
    }

    suspend fun barcodeInExtraTable(code: String): ProductEntity? = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query(
            "SELECT p.* FROM products p JOIN product_barcodes b ON b.productId=p.id WHERE b.barcode=? AND p.isActive=1 LIMIT 1",
            arrayOf(code.trim())
        ).use { cur ->
            if (!cur.moveToFirst()) return@use null
            fun s(name: String): String? { val i = cur.getColumnIndex(name); return if (i >= 0 && !cur.isNull(i)) cur.getString(i) else null }
            fun l(name: String): Long? { val i = cur.getColumnIndex(name); return if (i >= 0 && !cur.isNull(i)) cur.getLong(i) else null }
            fun d(name: String): Double { val i = cur.getColumnIndex(name); return if (i >= 0 && !cur.isNull(i)) cur.getDouble(i) else 0.0 }
            fun b(name: String): Boolean = (l(name) ?: 0L) != 0L
            ProductEntity(
                id = l("id") ?: 0,
                name = s("name") ?: "",
                alternativeName = s("alternativeName") ?: "",
                categoryId = l("categoryId"), brand = s("brand") ?: "", sku = s("sku") ?: "", barcode = s("barcode") ?: "",
                buyPrice = l("buyPrice") ?: 0, marketPrice = l("marketPrice") ?: 0, sellPrice = l("sellPrice") ?: 0,
                posSellPrice = l("posSellPrice") ?: 0, dynamicPriceEnabled = b("dynamicPriceEnabled"), commission = l("commission") ?: 0,
                customerCommission = l("customerCommission") ?: 0, customerCommissionPercentage = b("customerCommissionPercentage"),
                trackInventory = b("trackInventory"), stockQty = l("stockQty") ?: 0, lowStockAlert = l("lowStockAlert") ?: 5,
                uom = s("uom") ?: "PCS", uomName = s("uomName") ?: "Pieces", uomConverter = d("uomConverter"),
                uomBuyPrice = l("uomBuyPrice") ?: 0, uomSellPrice = l("uomSellPrice") ?: 0, uomSellPricePos = l("uomSellPricePos") ?: 0,
                qtyFastMoving = l("qtyFastMoving") ?: 0, weightKg = d("weightKg"), loyaltyPoints = l("loyaltyPoints") ?: 0,
                published = b("published"), posHidden = b("posHidden"), description = s("description") ?: "", photo = s("photo"),
                notes = s("notes") ?: "", taxFreeItem = b("taxFreeItem"), nonServiceCharge = b("nonServiceCharge"), isActive = b("isActive")
            )
        }
    }

    suspend fun lowStock(): List<ProductEntity> = withContext(Dispatchers.IO) { productDao.lowStock() }
    suspend fun outOfStock(): List<ProductEntity> = withContext(Dispatchers.IO) { productDao.outOfStock() }

    data class SaveResult(val ok: Boolean, val error: String?, val id: Long)
    private class Validation(message: String) : RuntimeException(message)

    suspend fun save(p: ProductEntity, userId: Long, initialQtyOverride: Long? = null): SaveResult = withContext(Dispatchers.IO) {
        if (p.name.isBlank()) return@withContext SaveResult(false, "Nama produk wajib diisi", 0)
        if (listOf(p.buyPrice, p.marketPrice, p.sellPrice, p.posSellPrice, p.lowStockAlert).any { it < 0 }) {
            return@withContext SaveResult(false, "Harga dan batas stok tidak boleh negatif", p.id)
        }
        try {
            var resultId = p.id
            db.withTransaction {
                Authorization.requireActiveAdmin(db, userId)
                var entity = p.copy(name = p.name.trim(), sku = p.sku.trim(), barcode = p.barcode.trim())
                if (entity.barcode.isNotBlank() && productDao.barcodeTaken(entity.barcode, entity.id) > 0) {
                    throw Validation("Barcode sudah dipakai produk lain")
                }
                if (entity.sku.isNotBlank() && productDao.skuTaken(entity.sku, entity.id) > 0) {
                    throw Validation("SKU sudah dipakai produk lain")
                }

                if (entity.id == 0L) {
                    if (entity.sku.isBlank()) {
                        var candidate: String
                        do {
                            val seq = settings.long("sku.seq", 1)
                            candidate = String.format("TRP-%06d", seq)
                            settings.putLong("sku.seq", seq + 1)
                        } while (productDao.skuTaken(candidate, 0) > 0)
                        entity = entity.copy(sku = candidate)
                    }
                    val initial = initialQtyOverride ?: entity.stockQty
                    if (initial < 0) throw Validation("Stok awal tidak boleh negatif")
                    val posPrice = entity.posSellPrice.takeIf { it > 0 } ?: entity.sellPrice
                    // Insert with zero first so stock and INITIAL movement become one indivisible write.
                    val inserted = entity.copy(stockQty = if (entity.trackInventory) 0 else entity.stockQty, posSellPrice = posPrice)
                    resultId = productDao.insert(inserted)
                    if (inserted.trackInventory && initial != 0L) {
                        productDao.setQty(resultId, initial)
                        inventoryDao.insert(
                            InventoryMovementEntity(
                                productId = resultId,
                                type = "INITIAL",
                                quantity = initial,
                                note = "Stok awal"
                            )
                        )
                    }
                    settings.audit(userId, "PRODUCT_CREATE", "product", resultId, "Tambah produk ${inserted.name}")
                } else {
                    val current = productDao.byId(entity.id) ?: throw Validation("Produk tidak ditemukan")
                    productDao.update(
                        entity.copy(
                            stockQty = current.stockQty,
                            isActive = current.isActive,
                            createdAt = current.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    settings.audit(userId, "PRODUCT_UPDATE", "product", entity.id, "Edit produk ${entity.name}")
                }
            }
            SaveResult(true, null, resultId)
        } catch (e: Exception) {
            SaveResult(false, e.message ?: "Gagal menyimpan produk", p.id)
        }
    }

    data class LifecycleResult(val ok: Boolean, val error: String? = null)

    suspend fun setActive(productId: Long, active: Boolean, userId: Long): LifecycleResult = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                Authorization.requireActiveAdmin(db, userId)
                val current = productDao.byId(productId) ?: throw Validation("Produk tidak ditemukan")
                if (current.isActive != active) {
                    val now = System.currentTimeMillis()
                    if (productDao.setActive(productId, active, now) != 1) throw Validation("Status produk gagal diperbarui")
                    settings.audit(
                        userId,
                        if (active) "PRODUCT_ACTIVATE" else "PRODUCT_DEACTIVATE",
                        "product",
                        productId,
                        if (active) "Aktifkan produk ${current.name}" else "Nonaktifkan produk ${current.name}"
                    )
                }
            }
            LifecycleResult(true)
        } catch (e: Exception) {
            LifecycleResult(false, e.message ?: "Gagal mengubah status produk")
        }
    }

    suspend fun hardDeleteIfUnused(id: Long, userId: Long): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        db.withTransaction {
            Authorization.requireActiveAdmin(db, userId)
            val used = db.openHelper.writableDatabase.compileStatement("SELECT COUNT(*) FROM sale_items WHERE productId=?").use { st ->
                st.bindLong(1, id); st.simpleQueryForLong()
            }
            if (used == 0L) { productDao.hardDelete(id); deleted = true }
        }
        deleted
    }

    suspend fun adjustStock(product: ProductEntity, mode: String, amount: Long, reason: String, userId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (mode !in setOf("ADD", "REMOVE", "SET")) return@withContext false
            if ((mode == "SET" && amount < 0) || (mode != "SET" && amount <= 0)) return@withContext false
            try {
                db.withTransaction {
                    Authorization.requireActiveAdmin(db, userId)
                    val current = productDao.byId(product.id) ?: throw Validation("Produk tidak ditemukan")
                    if (!current.trackInventory) throw Validation("Produk ini tidak melacak stok")
                    val delta = when (mode) {
                        "ADD" -> amount
                        "REMOVE" -> {
                            if (amount > current.stockQty) throw Validation("Stok tidak cukup")
                            -amount
                        }
                        else -> amount - current.stockQty
                    }
                    val newQty = current.stockQty + delta
                    if (newQty < 0) throw Validation("Stok tidak boleh negatif")
                    productDao.setQty(current.id, newQty)
                    inventoryDao.insert(
                        InventoryMovementEntity(
                            productId = current.id,
                            type = when (mode) { "ADD" -> "ADJUST_ADD"; "REMOVE" -> "ADJUST_REMOVE"; else -> "ADJUST_SET" },
                            quantity = delta,
                            note = reason.trim(),
                            userId = userId
                        )
                    )
                    settings.audit(userId, "STOCK_ADJUST", "product", current.id, "$mode $amount (${reason.trim()}) untuk ${current.name}")
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    suspend fun categoriesAll(): List<CategoryEntity> = withContext(Dispatchers.IO) { categoryDao.all() }
    fun categoriesFlow() = categoryDao.allFlow()

    data class CatResult(val ok: Boolean, val error: String?)

    suspend fun saveCategory(c: CategoryEntity, userId: Long): CatResult = withContext(Dispatchers.IO) {
        if (c.name.isBlank()) return@withContext CatResult(false, "Nama kategori wajib")
        try {
            db.withTransaction {
                Authorization.requireActiveAdmin(db, userId)
                val name = c.name.trim()
                val dup = categoryDao.byName(name)
                if (dup != null && dup.id != c.id) throw Validation("Kategori sudah ada")
                if (c.id == 0L) categoryDao.insert(c.copy(name = name))
                else categoryDao.update(c.copy(name = name, updatedAt = System.currentTimeMillis()))
            }
            CatResult(true, null)
        } catch (e: Exception) {
            CatResult(false, e.message ?: "Gagal menyimpan kategori")
        }
    }

    suspend fun deleteCategorySafe(id: Long, userId: Long): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            var message = "Terhapus"
            db.withTransaction {
                Authorization.requireActiveAdmin(db, userId)
                val used = categoryDao.productsUsing(id)
                if (used > 0) throw Validation("Kategori dipakai oleh $used produk; nonaktifkan saja.")
                categoryDao.delete(id)
            }
            Pair(true, message)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal menghapus kategori")
        }
    }

    suspend fun setCategoryActive(id: Long, active: Boolean, userId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            Authorization.requireActiveAdmin(db, userId)
            categoryDao.setActive(id, active)
        }
    }
}
