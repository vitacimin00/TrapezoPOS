package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.StoreDao
import com.trapezo.pos.data.entity.StoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StoreRepository(private val dao: StoreDao) {
    suspend fun get(): StoreEntity = withContext(Dispatchers.IO) {
        dao.primary() ?: StoreEntity(name = "Toko Saya").let { it.copy(id = dao.insert(it)) }
    }

    suspend fun save(store: StoreEntity): StoreEntity = withContext(Dispatchers.IO) {
        val cleaned = store.copy(name = store.name.trim().ifBlank { "Toko Saya" }, updatedAt = System.currentTimeMillis())
        if (cleaned.id == 0L) cleaned.copy(id = dao.insert(cleaned)) else { dao.update(cleaned); cleaned }
    }
}
