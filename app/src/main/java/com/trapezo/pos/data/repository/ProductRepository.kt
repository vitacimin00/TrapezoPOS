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

    // ---------- queries ----------
    suspend fun page(query: String, categoryId: Long?, includeInactive: Boolean, sort: String, page: Int, pageSize: Int = 50)
        = withContext(Dispatchers.IO) {
        val list = productDao.page(query.trim(), categoryId, includeInactive, sort, pageSize, page * pageSize)
        val total = productDao.countPage(query.trim(), categoryId, includeInactive)
        Pair(list, total)
    }

    suspend fun posSearch(q: String, limit: Int = 60, offset: Int = 0): List<ProductEntity> =
        withContext(Dispatchers.IO) { productDao.posSearch(q.trim(), limit, offset) }

    suspend fun byId(id: Long): ProductEntity? = withContext(Dispatchers.IO) { productDao.byId(id) }

    suspend fun byBarcode(code: String): ProductEntity? = withContext(Dispatchers.IO) {
        val c = code.trim()
        if (c.isEmpty()) return@withContext null
        productDao.byBarcode(c) ?: productDao.bySku(c)
        // multi-barcode table is checked by the POS flow when the primary lookup misses
    }

    suspend fun barcodeInExtraTable(code: String): ProductEntity? = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query(
            "SELECT p.* FROM products p JOIN product_barcodes b ON b.productId=p.id WHERE b.barcode=? LIMIT 1",
            arrayOf(code.trim())
        ).use { cur ->
            if (cur.moveToFirst()) {
                var i = 0
                fun s(name: String): String? { val idx = cur.getColumnIndex(name); return if (idx >= 0) (if (cur.isNull(idx)) null else cur.getString(idx)) else null }
                fun l(name: String): Long? { val idx = cur.getColumnIndex(name); return if (idx >= 0 && !cur.isNull(idx)) cur.getLong(idx) else null }
                fun d(name: String): Double { val idx = cur.getColumnIndex(name); return if (idx >= 0 && !cur.isNull(idx)) cur.getDouble(idx) else 0.0 }
                fun bl(name: String): Boolean { val v = l(name); return v != null && v != 0L }
                ProductEntity(
                    id = l("id") ?: 0,
                    name = s("name") ?: "",
                    alternativeName = s("alternativeName") ?: "",
                    categoryId = l("categoryId"),
                    brand = s("brand") ?: "",
                    sku = s("sku") ?: "",
                    barcode = s("barcode") ?: "",
                    buyPrice = l("buyPrice") ?: 0,
                    marketPrice = l("marketPrice") ?: 0,
                    sellPrice = l("sellPrice") ?: 0,
                    posSellPrice = l("posSellPrice") ?: 0,
                    dynamicPriceEnabled = bl("dynamicPriceEnabled"),
                    commission = l("commission") ?: 0,
                    customerCommission = l("customerCommission") ?: 0,
                    customerCommissionPercentage = bl("customerCommissionPercentage"),
                    trackInventory = bl("trackInventory"),
                    stockQty = l("stockQty") ?: 0,
                    lowStockAlert = l("lowStockAlert") ?: 5,
                    uom = s("uom") ?: "PCS",
                    uomName = s("uomName") ?: "Pieces",
                    uomConverter = d("uomConverter"),
                    uomBuyPrice = l("uomBuyPrice") ?: 0,
                    uomSellPrice = l("uomSellPrice") ?: 0,
                    uomSellPricePos = l("uomSellPricePos") ?: 0,
                    qtyFastMoving = l("qtyFastMoving") ?: 0,
                    weightKg = d("weightKg"),
                    loyaltyPoints = l("loyaltyPoints") ?: 0,
                    published = bl("published"),
                    posHidden = bl("posHidden"),
                    description = s("description") ?: "",
                    photo = s("photo"),
                    notes = s("notes") ?: "",
                    taxFreeItem = bl("taxFreeItem"),
                    nonServiceCharge = bl("nonServiceCharge"),
                    isActive = bl("isActive")
                )
            } else null
        }
    }

    suspend fun lowStock(): List<ProductEntity> = withContext(Dispatchers.IO) { productDao.lowStock() }
    suspend fun outOfStock(): List<ProductEntity> = withContext(Dispatchers.IO) { productDao.outOfStock() }

    // ---------- mutations ----------
    data class SaveResult(val ok: Boolean, val error: String?, val id: Long)

    suspend fun save(p: ProductEntity, initialQtyOverride: Long? = null): SaveResult {
        return withContext(Dispatchers.IO) {
            // validations
            if (p.name.isBlank()) return@withContext SaveResult(false, "Nama produk wajib diisi", 0)
            if (p.barcode.isNotBlank() && productDao.barcodeTaken(p.barcode, p.id) > 0)
                return@withContext SaveResult(false, "Barcode sudah dipakai produk lain", 0)
            if (p.sku.isNotBlank() && productDao.skuTaken(p.sku, p.id) > 0)
                return@withContext SaveResult(false, "SKU sudah dipakai produk lain", 0)

            val isNew = p.id == 0L
            if (isNew) {
                var entity = p
                if (entity.sku.isBlank()) {
                    // auto SKU TRP-000001
                    val seq = settings.long("sku.seq", 1)
                    entity = entity.copy(sku = String.format("TRP-%06d", seq))
                    settings.putLong("sku.seq", seq + 1)
                }
                val price = entity.posSellPrice.takeIf { it > 0 } ?: entity.sellPrice
                entity = entity.copy(posSellPrice = price)
                val id = productDao.insert(entity)
                val initQty = initialQtyOverride ?: entity.stockQty
                if (entity.trackInventory && initQty != 0L) {
                    productDao.setQty(id, initQty)
                    inventoryDao.insert(
                        InventoryMovementEntity(
                            productId = id, type = "INITIAL", quantity = initQty,
                            note = "Stok awal", userId = null
                        )
                    )
                }
                settings.audit(null, "PRODUCT_CREATE", "product", id, "Tambah produk ${entity.name}")
                SaveResult(true, null, id)
            } else {
                // Stock is immutable through the general product editor. All stock changes
                // must pass through adjustStock()/import so an inventory movement exists.
                val current = productDao.byId(p.id)
                    ?: return@withContext SaveResult(false, "Produk tidak ditemukan", p.id)
                productDao.update(
                    p.copy(
                        stockQty = current.stockQty,
                        createdAt = current.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                settings.audit(null, "PRODUCT_UPDATE", "product", p.id, "Edit produk ${p.name}")
                SaveResult(true, null, p.id)
            }
        }
    }

    suspend fun softDelete(p: ProductEntity) = withContext(Dispatchers.IO) {
        productDao.softDelete(p.id)
        settings.audit(null, "PRODUCT_DEACTIVATE", "product", p.id, "Nonaktifkan produk ${p.name}")
    }

    suspend fun hardDeleteIfUnused(id: Long): Boolean = withContext(Dispatchers.IO) {
        val used = db.openHelper.writableDatabase
            .compileStatement("SELECT COUNT(*) FROM sale_items WHERE productId=?").use { st ->
                st.bindLong(1, id); st.simpleQueryForLong()
            }
        if (used > 0) false else {
            productDao.hardDelete(id); true
        }
    }

    suspend fun adjustStock(product: ProductEntity, mode: String, amount: Long, reason: String, userId: Long?): Boolean {
        return withContext(Dispatchers.IO) {
            android.util.Log.d("ProductRepo", "adjustStock id=${product.id} mode=$mode amount=$amount")
            val delta = when (mode) {
                "ADD" -> amount
                "REMOVE" -> {
                    val current = productDao.stockOf(product.id) ?: 0L
                    if (amount > current) return@withContext false
                    -amount
                }
                "SET" -> {
                    val cur = productDao.stockOf(product.id) ?: 0
                    amount - cur
                }
                else -> return@withContext false
            }
            db.withTransaction {
                productDao.applyDelta(product.id, delta)
                inventoryDao.insert(
                    InventoryMovementEntity(
                        productId = product.id,
                        type = when (mode) { "ADD" -> "ADJUST_ADD"; "REMOVE" -> "ADJUST_REMOVE"; else -> "ADJUST_SET" },
                        quantity = delta,
                        note = reason, userId = userId
                    )
                )
            }
            settings.audit(userId, "STOCK_ADJUST", "product", product.id, "$mode $amount (${reason}) untuk ${product.name}")
            true
        }
    }

    // ---------- categories ----------
    suspend fun categoriesAll(): List<CategoryEntity> = categoryDao.all()
    fun categoriesFlow() = categoryDao.allFlow()

    data class CatResult(val ok: Boolean, val error: String?)
    suspend fun saveCategory(c: CategoryEntity): CatResult = withContext(Dispatchers.IO) {
        if (c.name.isBlank()) return@withContext CatResult(false, "Nama kategori wajib")
        val dup = categoryDao.byName(c.name.trim())
        if (dup != null && dup.id != c.id) return@withContext CatResult(false, "Kategori sudah ada")
        if (c.id == 0L) categoryDao.insert(c.copy(name = c.name.trim())) else categoryDao.update(c.copy(name = c.name.trim(), updatedAt = System.currentTimeMillis()))
        CatResult(true, null)
    }

    suspend fun deleteCategorySafe(id: Long): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val used = categoryDao.productsUsing(id)
        if (used > 0) Pair(false, "Kategori dipakai oleh $used produk; nonaktifkan saja.")
        else { categoryDao.delete(id); Pair(true, "Terhapus") }
    }

    suspend fun setCategoryActive(id: Long, active: Boolean) = withContext(Dispatchers.IO) {
        categoryDao.setActive(id, active)
    }
}
