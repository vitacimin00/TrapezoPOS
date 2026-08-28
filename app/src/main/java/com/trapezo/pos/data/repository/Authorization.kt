package com.trapezo.pos.data.repository

import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.UserEntity

/**
 * Central repository-layer authorization decision. Every administrative write
 * must resolve an authenticated, active ADMIN actor before mutating data —
 * never trust Compose navigation or a nullable actor id.
 */
internal object Authorization {

    /** Pure decision rule (unit-testable without a database). */
    fun denyReason(actor: UserEntity?): String? = when {
        actor == null -> "Akun tidak ditemukan"
        !actor.isActive -> "Akun tidak aktif"
        actor.role != "ADMIN" -> "Hanya admin aktif yang dapat melakukan aksi ini"
        else -> null
    }

    /** Loads and validates the actor in one call; throws IllegalArgumentException when denied. */
    suspend fun requireActiveAdmin(db: AppDatabase, userId: Long): UserEntity {
        val actor = db.userDao().byId(userId)
        denyReason(actor)?.let { throw IllegalArgumentException(it) }
        return actor!!
    }
}
