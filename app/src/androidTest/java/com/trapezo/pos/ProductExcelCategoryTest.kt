package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.excel.ProductExcelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductExcelCategoryTest {
    private lateinit var db: com.trapezo.pos.data.database.AppDatabase
    private lateinit var service: ProductExcelService
    private var adminId = 0L
    private var drinksId = 0L
    private var snacksId = 0L

    @Before fun setUp() { runBlocking {
        db = TestDb.inMemory()
        adminId = db.userDao().insert(TestDb.admin(active = true))
        drinksId = db.categoryDao().insert(CategoryEntity(name = "Drinks"))
        snacksId = db.categoryDao().insert(CategoryEntity(name = "Snacks"))
        db.productDao().insert(ProductEntity(name = "Coffee", sku = "CAT-1", sellPrice = 1000, categoryId = drinksId))
        service = ProductExcelService(db)
    } }

    @After fun tearDown() { db.close() }

    private fun preview(category: String): ProductExcelService.Preview {
        val values = ProductExcelService.HEADERS.associateWith { "" }.toMutableMap().apply {
            put("name", "Coffee"); put("sku", "CAT-1"); put("sell_price", "2000"); put("category", category)
        }
        return previewOf(values)
    }

    private fun createPreview(
        name: String,
        sku: String = "",
        barcode: String = "",
        stock: String = "0",
        tracked: String = "true"
    ): ProductExcelService.Preview {
        val values = ProductExcelService.HEADERS.associateWith { "" }.toMutableMap().apply {
            put("name", name); put("sku", sku); put("barcode", barcode)
            put("sell_price", "1000"); put("stock_qty", stock); put("track_inventory", tracked)
        }
        return previewOf(values)
    }

    private fun previewOf(values: Map<String, String>): ProductExcelService.Preview {
        val row = ProductExcelService.ImportRow(2, values, emptyList(), duplicate = true)
        return ProductExcelService.Preview("product", 1, 0, 0, 1, listOf(row), listOf(row))
    }

    @Test fun updateWithBlankCategory_preservesExistingCategory() { runBlocking {
        val result = service.import(preview(""), ProductExcelService.DuplicatePolicy.UPDATE, userId = adminId)
        assertEquals(1, result.updated)
        assertEquals(drinksId, db.productDao().bySku("CAT-1")!!.categoryId)
    } }

    @Test fun updateWithNamedCategory_changesCategory() { runBlocking {
        val result = service.import(preview("Snacks"), ProductExcelService.DuplicatePolicy.UPDATE, userId = adminId)
        assertEquals(1, result.updated)
        assertEquals(snacksId, db.productDao().bySku("CAT-1")!!.categoryId)
    } }

    @Test fun createNewWithDuplicateSku_generatesNonConflictingSku() { runBlocking {
        val oldId = db.productDao().insert(ProductEntity(name = "Old SKU", sku = "ABC-1"))
        val result = service.import(createPreview("New SKU", sku = "ABC-1"), ProductExcelService.DuplicatePolicy.CREATE_NEW, userId = adminId)
        val created = db.productDao().page("New SKU", null, true, "name_asc", 10, 0).single()
        assertEquals(1, result.imported)
        assertEquals("ABC-1", db.productDao().byId(oldId)!!.sku)
        assertNotEquals(oldId, created.id)
        assertNotEquals("ABC-1", created.sku)
        assertTrue(created.sku.isNotBlank())
    } }

    @Test fun createNewWithDuplicateBarcode_clearsBarcodeAndCreatesProduct() { runBlocking {
        db.productDao().insert(ProductEntity(name = "Old Barcode", sku = "OLD-B", barcode = "899001"))
        val result = service.import(createPreview("New Barcode", sku = "NEW-B", barcode = "899001"), ProductExcelService.DuplicatePolicy.CREATE_NEW, userId = adminId)
        val created = db.productDao().bySku("NEW-B")!!
        assertEquals(1, result.imported)
        assertEquals("", created.barcode)
    } }

    @Test fun createNewWithDuplicateSkuAndBarcode_generatesSkuAndClearsBarcode() { runBlocking {
        db.productDao().insert(ProductEntity(name = "Old Both", sku = "BOTH-1", barcode = "899002"))
        val result = service.import(createPreview("New Both", sku = "BOTH-1", barcode = "899002"), ProductExcelService.DuplicatePolicy.CREATE_NEW, userId = adminId)
        val created = db.productDao().page("New Both", null, true, "name_asc", 10, 0).single()
        assertEquals(1, result.imported)
        assertNotEquals("BOTH-1", created.sku)
        assertTrue(created.sku.isNotBlank())
        assertEquals("", created.barcode)
    } }

    @Test fun createNewWithSkuAndBarcodeCollidingWithDifferentProducts_resolvesBoth() { runBlocking {
        db.productDao().insert(ProductEntity(name = "Old SKU only", sku = "SPLIT-1"))
        db.productDao().insert(ProductEntity(name = "Old Barcode only", sku = "SPLIT-2", barcode = "899003"))
        val result = service.import(createPreview("New Split", sku = "SPLIT-1", barcode = "899003"), ProductExcelService.DuplicatePolicy.CREATE_NEW, userId = adminId)
        val created = db.productDao().page("New Split", null, true, "name_asc", 10, 0).single()
        assertEquals(1, result.imported)
        assertNotEquals("SPLIT-1", created.sku)
        assertTrue(created.sku.isNotBlank())
        assertEquals("", created.barcode)
    } }

    @Test fun trackedCreate_appliesInitialStockOnceThroughMovementLedger() { runBlocking {
        val result = service.import(createPreview("Stocked", sku = "STOCK-25", stock = "25"), ProductExcelService.DuplicatePolicy.CREATE_NEW, userId = adminId)
        val created = db.productDao().bySku("STOCK-25")!!
        val movements = db.inventoryDao().forProduct(created.id)
        assertEquals(1, result.imported)
        assertEquals(25L, created.stockQty)
        assertEquals(1, movements.size)
        assertEquals("IMPORT", movements.single().type)
        assertEquals(25L, movements.single().quantity)
    } }
}
