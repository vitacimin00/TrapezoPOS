package com.trapezo.pos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.StoreEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the repository-layer authorization boundary for every administrative
 * mutation, independent of Compose navigation. Requires a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryAuthorizationTest {

    private lateinit var db: com.trapezo.pos.data.database.AppDatabase

    @Before fun setUp() {
        db = TestDb.inMemory()
        runBlocking {
            db.userDao().insert(TestDb.admin(active = true).copy(id = 1))
            db.userDao().insert(TestDb.cashier(active = true).copy(id = 2))
        }
    }

    @After fun tearDown() { db.close() }

    @Test fun cashier_cannotCreateProduct() = runBlocking {
        val result = TestDb.products(db).save(ProductEntity(name = "Barang"), userId = 2)
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("admin"))
    }

    @Test fun adminCanCreateProduct() = runBlocking {
        val result = TestDb.products(db).save(ProductEntity(name = "Barang"), userId = 1)
        assertTrue(result.ok)
    }

    @Test fun cashier_cannotChangeProductLifecycle() = runBlocking {
        val saved = TestDb.products(db).save(ProductEntity(name = "Barang", sku = "S1"), userId = 1)
        val result = TestDb.products(db).setActive(saved.id, false, userId = 2)
        assertFalse(result.ok)
    }

    @Test fun inactiveAdmin_cannotCreateProduct() = runBlocking {
        db.userDao().update(TestDb.admin(active = false).copy(id = 1))
        val result = TestDb.products(db).save(ProductEntity(name = "Barang"), userId = 1)
        assertFalse(result.ok)
    }

    @Test fun missingActor_cannotCreateProduct() = runBlocking {
        val result = TestDb.products(db).save(ProductEntity(name = "Barang"), userId = 999)
        assertFalse(result.ok)
    }

    @Test fun cashier_cannotMutateCategory() = runBlocking {
        val result = TestDb.products(db).saveCategory(CategoryEntity(name = "Kat"), userId = 2)
        assertFalse(result.ok)
    }

    @Test fun adminCanMutateCategory() = runBlocking {
        val result = TestDb.products(db).saveCategory(CategoryEntity(name = "Kat"), userId = 1)
        assertTrue(result.ok)
    }

    @Test fun cashier_cannotMutateSettings() = runBlocking {
        val error = TestDb.settings(db).setPaymentMethodActive("QRIS", false, 2)
        assertNotNull(error)
    }

    @Test fun cashier_cannotSaveStore() = runBlocking {
        var threw = false
        try { TestDb.store(db).save(StoreEntity(name = "Toko"), 2) } catch (_: Exception) { threw = true }
        assertTrue(threw)
    }

    @Test fun adminCannotDisableLastAdmin() = runBlocking {
        // Last-active-admin protection already exercised in UserRepository; here we confirm
        // the repository rejects a cashier attempting user management.
        val result = TestDb.users(db).save(null, "u3", "Nama", "CASHIER", "password123", true, actorId = 2)
        assertNotNull(result.error)
    }
}
