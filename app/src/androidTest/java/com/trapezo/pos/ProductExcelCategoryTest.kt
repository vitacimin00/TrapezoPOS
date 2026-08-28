package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.excel.ProductExcelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After fun tearDown() = db.close()

    private fun preview(category: String): ProductExcelService.Preview {
        val values = ProductExcelService.HEADERS.associateWith { "" }.toMutableMap().apply {
            put("name", "Coffee"); put("sku", "CAT-1"); put("sell_price", "2000"); put("category", category)
        }
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
}
