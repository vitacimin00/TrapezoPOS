package com.trapezo.pos.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.trapezo.pos.data.entity.UserEntity

/** Authentication and administration users DAO. */
@Dao
interface UserDao {
    @Insert
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT * FROM users WHERE username = :username COLLATE NOCASE LIMIT 1")
    suspend fun byUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): UserEntity?

    @Query("SELECT * FROM users ORDER BY role DESC, name ASC")
    suspend fun all(): List<UserEntity>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND isActive=1")
    suspend fun countActiveAdmins(): Int

    @Query("UPDATE users SET isActive = :active, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE users SET failedLoginCount=:count, lockedUntil=:lockedUntil, updatedAt=:now WHERE id=:id")
    suspend fun recordFailedLogin(id: Long, count: Int, lockedUntil: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE users SET failedLoginCount=0, lockedUntil=0, updatedAt=:now WHERE id=:id")
    suspend fun clearLoginFailures(id: Long, now: Long = System.currentTimeMillis())
}
