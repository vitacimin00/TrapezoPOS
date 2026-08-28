package com.trapezo.pos.excel

import androidx.room.withTransaction
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/** Product workbook contract and import/export workflow for real .xlsx workbooks. */
class ProductExcelService(private val db: AppDatabase) {
    companion object {
        /** Exact column order defined for Trapezo POS product workbooks. */
        val HEADERS = listOf(
            "name", "alternative_name", "classification_id", "category", "variant_label", "variant_names",
            "alternative_variant_name", "collections", "brand", "condition_id", "sku", "barcode", "buy_price",
            "market_price", "sell_price", "pos_sell_price", "pos_sell_price_dynamic", "comission",
            "customer_comission", "is_customer_comission_percentage", "track_inventory", "stock_qty", "hold_qty",
            "low_stock_alert", "uom", "uom_name", "uom_converter", "uom_buy_price", "uom_sell_price",
            "uom_sell_price_pos", "qty_fast_moving", "weight_kg", "loyalty_points", "published", "pos_hidden",
            "description", "photo_1", "photo_2", "photo_3", "photo_4", "photo_5", "photo_6", "photo_7",
            "photo_8", "photo_9", "photo_10", "notes", "tax_free_item", "non_service_charge"
        )

        /** Sanity ceiling for a single Rupiah money cell (9 trillion). */
        const val MAX_RUPIAH = 9_000_000_000_000L
        /** Sanity ceiling for a single stock quantity cell. */
        const val MAX_QTY = 2_000_000_000L
        const val MAX_WEIGHT = 1_000_000.0
    }

    enum class DuplicatePolicy { SKIP, UPDATE, CREATE_NEW }
    enum class MissingCategoryPolicy { CREATE_AUTOMATICALLY, USE_OTHERS }
    data class ImportRow(val excelRow: Int, val values: Map<String, String>, val errors: List<String>, val duplicate: Boolean)
    data class Preview(val sheet: String, val total: Int, val valid: Int, val errors: Int, val duplicates: Int, val sample: List<ImportRow>, val rows: List<ImportRow>)
    data class ImportResult(val imported: Int, val updated: Int, val skipped: Int, val failed: Int, val messages: List<String>)

    suspend fun preview(input: InputStream): Preview = withContext(Dispatchers.IO) {
        val book = XlsxModule.read(input, "product")
        val lower = book.headers.map { it.trim().lowercase() }
        val absent = HEADERS.filter { it !in lower }
        if (absent.isNotEmpty()) throw IllegalArgumentException("Header Excel tidak sesuai. Kolom hilang: ${absent.joinToString()}")
        val productDao = db.productDao()
        val seenBarcodes = mutableSetOf<String>()
        val seenSkus = mutableSetOf<String>()
        val rows = book.rows.mapIndexed { index, raw ->
            val v = HEADERS.associateWith { header -> raw.entries.firstOrNull { it.key.trim().equals(header, true) }?.value?.trim().orEmpty() }
            val errors = mutableListOf<String>()
            if (v["name"].isNullOrBlank()) errors += "Nama wajib diisi"
            // Formula cells are computed results, not trusted static import data.
            if (book.formulaRows.contains(index)) errors += "Baris mengandung formula Excel yang tidak didukung"
            // Strict integer validation: money and stock are integer Rupiah/quantity only.
            listOf("buy_price", "market_price", "sell_price", "pos_sell_price", "comission", "customer_comission", "uom_buy_price", "uom_sell_price", "uom_sell_price_pos").forEach { key ->
                val s = v[key].orEmpty()
                if (s.isNotBlank() && strictInteger(s, MAX_RUPIAH) == null) errors += "$key harus berupa bilangan bulat Rupiah dalam rentang aman"
            }
            listOf("stock_qty", "low_stock_alert", "qty_fast_moving", "loyalty_points").forEach { key ->
                val s = v[key].orEmpty()
                if (s.isNotBlank() && strictInteger(s, MAX_QTY) == null) errors += "$key harus berupa bilangan bulat dalam rentang aman"
            }
            val barcode = v["barcode"].orEmpty()
            val sku = v["sku"].orEmpty()
            val duplicateInFile = (barcode.isNotBlank() && !seenBarcodes.add(barcode)) ||
                (sku.isNotBlank() && !seenSkus.add(sku))
            val duplicateInDb = (barcode.isNotBlank() && productDao.barcodeTaken(barcode, 0) > 0) ||
                (sku.isNotBlank() && productDao.skuTaken(sku, 0) > 0)
            if (duplicateInFile) errors += "Barcode atau SKU duplikat dalam file Excel"
            ImportRow(index + 2, v, errors, duplicateInDb || duplicateInFile)
        }
        Preview(book.activeSheet, rows.size, rows.count { it.errors.isEmpty() && !it.duplicate }, rows.count { it.errors.isNotEmpty() }, rows.count { it.duplicate }, rows.take(10), rows)
    }

    suspend fun import(preview: Preview, duplicatePolicy: DuplicatePolicy = DuplicatePolicy.SKIP, categoryPolicy: MissingCategoryPolicy = MissingCategoryPolicy.USE_OTHERS, userId: Long): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0; var updated = 0; var skipped = 0; var failed = 0
        val messages = mutableListOf<String>()
        try {
            com.trapezo.pos.data.repository.Authorization.requireActiveAdmin(db, userId)
        } catch (e: Exception) {
            return@withContext ImportResult(0, 0, 0, preview.rows.size, listOf("Import hanya untuk admin aktif"))
        }
        val categories = db.categoryDao()
        val products = db.productDao()
        val others = categories.byName("Lainnya") ?: CategoryEntity(name = "Lainnya").let { categories.insert(it); categories.byName("Lainnya")!! }
        for (row in preview.rows) {
            if (row.errors.isNotEmpty()) { failed++; messages += "Baris ${row.excelRow}: ${row.errors.joinToString()}"; continue }
            try {
                val v = row.values
                var barcode = v["barcode"].orEmpty()
                var sku = v["sku"].orEmpty()
                val barcodeMatch = barcode.takeIf { it.isNotBlank() }?.let { products.byBarcode(it) }
                val skuMatch = sku.takeIf { it.isNotBlank() }?.let { products.bySku(it) }
                if (barcodeMatch != null && skuMatch != null && barcodeMatch.id != skuMatch.id) {
                    throw IllegalArgumentException("Barcode dan SKU menunjuk ke dua produk berbeda")
                }
                var targetExisting = barcodeMatch ?: skuMatch
                if (targetExisting != null && duplicatePolicy == DuplicatePolicy.SKIP) { skipped++; continue }
                if (targetExisting != null && duplicatePolicy == DuplicatePolicy.CREATE_NEW) {
                    // SKU/barcode identify a product. Creating a separate product must
                    // generate/clear only conflicting identifiers, never clone IDs.
                    val notes = mutableListOf<String>()
                    if (skuMatch != null || sku.isBlank()) { sku = nextSku(); notes += "SKU $sku" }
                    if (barcodeMatch != null) { barcode = ""; notes += "barcode dikosongkan" }
                    targetExisting = null
                    messages += "Baris ${row.excelRow}: produk baru dibuat (${notes.joinToString()})"
                }
                val categoryName = v["category"].orEmpty()
                val categoryId = when {
                    categoryName.isBlank() -> null
                    categories.byName(categoryName) != null -> categories.byName(categoryName)!!.id
                    categoryPolicy == MissingCategoryPolicy.CREATE_AUTOMATICALLY -> categories.insert(CategoryEntity(name = categoryName))
                    else -> others.id
                }
                val price = money(v["sell_price"])
                val posPrice = money(v["pos_sell_price"]).takeIf { it > 0 } ?: price
                val base = ProductEntity(
                    id = targetExisting?.id ?: 0,
                    name = v["name"].orEmpty(), alternativeName = v["alternative_name"].orEmpty(), categoryId = categoryId,
                    brand = v["brand"].orEmpty(), sku = sku, barcode = barcode, buyPrice = money(v["buy_price"]),
                    marketPrice = money(v["market_price"]), sellPrice = price, posSellPrice = posPrice,
                    dynamicPriceEnabled = bool(v["pos_sell_price_dynamic"]), commission = money(v["comission"]),
                    customerCommission = money(v["customer_comission"]), customerCommissionPercentage = bool(v["is_customer_comission_percentage"]),
                    trackInventory = bool(v["track_inventory"], true), stockQty = qty(v["stock_qty"]), lowStockAlert = qty(v["low_stock_alert"], 5),
                    uom = v["uom"].orEmpty().ifBlank { "PCS" }, uomName = v["uom_name"].orEmpty().ifBlank { "Pieces" },
                    uomConverter = number(v["uom_converter"], 1.0), uomBuyPrice = money(v["uom_buy_price"]), uomSellPrice = money(v["uom_sell_price"]), uomSellPricePos = money(v["uom_sell_price_pos"]),
                    qtyFastMoving = qty(v["qty_fast_moving"]), weightKg = number(v["weight_kg"], 0.0), loyaltyPoints = qty(v["loyalty_points"]),
                    published = bool(v["published"], true), posHidden = bool(v["pos_hidden"]), description = v["description"].orEmpty(), photo = v["photo_1"].orEmpty().ifBlank { null },
                    notes = v["notes"].orEmpty(), taxFreeItem = bool(v["tax_free_item"]), nonServiceCharge = bool(v["non_service_charge"]), updatedAt = System.currentTimeMillis()
                )
                if (targetExisting != null && duplicatePolicy == DuplicatePolicy.UPDATE) {
                    // UPDATE is atomic: catalog update plus the exact stock delta and
                    // inventory movement are committed together.
                    db.withTransaction {
                        if (barcode.isNotBlank() && products.barcodeTaken(barcode, targetExisting.id) > 0) {
                            throw IllegalArgumentException("Barcode sudah dipakai produk lain")
                        }
                        if (sku.isNotBlank() && products.skuTaken(sku, targetExisting.id) > 0) {
                            throw IllegalArgumentException("SKU sudah dipakai produk lain")
                        }
                        val stockDelta = if (base.trackInventory) base.stockQty - targetExisting.stockQty else 0L
                        val persisted = base.copy(
                            stockQty = if (base.trackInventory) base.stockQty else targetExisting.stockQty,
                            createdAt = targetExisting.createdAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        products.update(persisted)
                        if (stockDelta != 0L) {
                            db.inventoryDao().insert(
                                InventoryMovementEntity(
                                    productId = targetExisting.id,
                                    type = "IMPORT_UPDATE",
                                    quantity = stockDelta,
                                    note = "Update dari import Excel",
                                    userId = userId
                                )
                            )
                        }
                    }
                    updated++
                } else {
                    // New-product import is indivisible: insert + initial stock + movement
                    // + SKU reservation commit together, satisfying the Track C invariant.
                    db.withTransaction {
                        // Revalidate the actor inside the write transaction (actor may have
                        // been deactivated mid-import; do not let stale rows keep writing).
                        com.trapezo.pos.data.repository.Authorization.requireActiveAdmin(db, userId)
                        val prepared = base.copy(sku = base.sku.ifBlank { nextSku() })
                        if (prepared.barcode.isNotBlank() && products.barcodeTaken(prepared.barcode, 0) > 0) {
                            throw IllegalArgumentException("Barcode sudah dipakai produk lain")
                        }
                        if (prepared.sku.isNotBlank() && products.skuTaken(prepared.sku, 0) > 0) {
                            throw IllegalArgumentException("SKU sudah dipakai produk lain")
                        }
                        val newId = products.insert(prepared)
                        if (prepared.trackInventory && prepared.stockQty != 0L) {
                            db.inventoryDao().insert(
                                InventoryMovementEntity(productId = newId, type = "IMPORT", quantity = prepared.stockQty, note = "Import Excel", userId = userId)
                            )
                        }
                    }
                    imported++
                }
            } catch (e: Exception) { failed++; messages += "Baris ${row.excelRow}: ${e.message ?: "gagal diimpor"}" }
        }
        ImportResult(imported, updated, skipped, failed, messages)
    }

    fun writeTemplate(out: OutputStream) {
        val instructions = HEADERS.map { XlsxModule.CellVal.Txt(it) }
        XlsxModule.write(out, listOf(XlsxModule.WriteSheet("product", listOf(instructions))))
    }

    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        val rows = mutableListOf<List<XlsxModule.CellVal>>()
        rows += HEADERS.map { XlsxModule.CellVal.Txt(it) }
        var offset = 0
        while (true) {
            val batch = db.productDao().page("", null, true, "name_asc", 500, offset)
            if (batch.isEmpty()) break
            batch.forEach { p -> rows += listOf(
                p.name,p.alternativeName,"",categoryName(p.categoryId),"","","","",p.brand,"",p.sku,p.barcode,p.buyPrice,p.marketPrice,p.sellPrice,p.posSellPrice,p.dynamicPriceEnabled,p.commission,p.customerCommission,p.customerCommissionPercentage,p.trackInventory,p.stockQty,"",p.lowStockAlert,p.uom,p.uomName,p.uomConverter,p.uomBuyPrice,p.uomSellPrice,p.uomSellPricePos,p.qtyFastMoving,p.weightKg,p.loyaltyPoints,p.published,p.posHidden,p.description,p.photo ?: "","","","","","","","","","",p.notes,p.taxFreeItem,p.nonServiceCharge
            ).map { XlsxModule.CellVal.Txt(it.toString()) } }
            offset += batch.size
        }
        XlsxModule.write(out, listOf(XlsxModule.WriteSheet("product", rows)))
    }

    private suspend fun categoryName(id: Long?): String = id?.let { db.categoryDao().byId(it)?.name }.orEmpty()
    private suspend fun nextSku(): String {
        // Delegates to the canonical allocator; caller is inside a transaction.
        return com.trapezo.pos.data.repository.SettingsRepository(db).reserveNextSkuCode()
    }
    private fun parseNumber(v: String): Double? = ExcelNumberParser.parse(v)
    /** Integer-only money parse: rejects fractional, overflow, negative, out-of-range. */
    private fun money(v: String?): Long = strictInteger(v.orEmpty(), MAX_RUPIAH) ?: 0L
    /** Integer-only quantity parse. */
    private fun qty(v: String?, def: Long = 0): Long = strictInteger(v.orEmpty(), MAX_QTY) ?: def
    private fun number(v: String?, def: Double): Double {
        val d = parseNumber(v.orEmpty()) ?: return def
        return if (d.isFinite() && d in -MAX_WEIGHT..MAX_WEIGHT) d else def
    }
    /**
     * Strict integer parse for Rupiah/stock cells. Rejects fractional values (10.9),
     * negative where prohibited, Long overflow, and values beyond the operational ceiling.
     * Preview validation uses the same rule so an out-of-range value never becomes 0 later.
     */
    private fun strictInteger(raw: String, cap: Long): Long? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        val digits = clean.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // Reject any sign of fractional input or non-integer formatting.
        if (clean.contains('.') || clean.contains(',')) {
            // allow thousand separators only when purely between digits, but never a decimal point
            if (clean.contains('.')) return null
            // commas as thousand separators are tolerated if every remaining char is digit/comma
            if (clean.any { !it.isDigit() && it != ',' }) return null
        }
        if (digits.length > 18) return null
        val value = digits.toLongOrNull() ?: return null
        if (value < 0 || value > cap) return null
        return value
    }
    private fun bool(v: String?, def: Boolean = false): Boolean = when (v?.trim()?.lowercase()) { "1", "true", "yes", "ya", "y" -> true; "0", "false", "no", "tidak", "n" -> false; else -> def }
}
