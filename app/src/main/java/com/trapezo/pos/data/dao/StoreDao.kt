package com.trapezo.pos.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.trapezo.pos.data.entity.StoreEntity

@Dao
interface StoreDao {
    @Query("SELECT * FROM stores ORDER BY id ASC LIMIT 1")
    suspend fun primary(): StoreEntity?

    @Insert
    suspend fun insert(store: StoreEntity): Long

    @Update
    suspend fun update(store: StoreEntity)
}
