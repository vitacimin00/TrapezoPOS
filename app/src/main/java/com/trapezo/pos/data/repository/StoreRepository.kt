package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.StoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StoreRepository(private val db: AppDatabase) {
    private val dao get() = db.storeDao()

    suspend fun get(): StoreEntity = withContext(Dispatchers.IO) {
        dao.primary() ?: StoreEntity(name = "Toko Saya").let { it.copy(id = dao.insert(it)) }
    }

    suspend fun save(store: StoreEntity, actorId: Long): StoreEntity = withContext(Dispatchers.IO) {
        val cleaned = store.copy(name = store.name.trim().ifBlank { "Toko Saya" }, updatedAt = System.currentTimeMillis())
        db.withTransaction {
            Authorization.requireActiveAdmin(db, actorId)
            if (cleaned.id == 0L) cleaned.copy(id = dao.insert(cleaned)) else { dao.update(cleaned); cleaned }
        }
    }
}
