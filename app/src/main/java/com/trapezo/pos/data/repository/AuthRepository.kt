package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.UserDao
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.utils.PasswordUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(private val users: UserDao) {
    suspend fun login(username: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Username dan password wajib diisi"))
        }
        val user = users.byUsername(username.trim())
            ?: return@withContext Result.failure(IllegalArgumentException("Username atau password salah"))
        if (!user.isActive) return@withContext Result.failure(IllegalArgumentException("Akun ini tidak aktif"))
        if (!PasswordUtil.verify(password, user.passwordHash)) {
            return@withContext Result.failure(IllegalArgumentException("Username atau password salah"))
        }

        // Transparently migrate the first-build legacy seed / lower work factor
        // only after the correct password has been supplied.
        val authenticated = if (PasswordUtil.needsRehash(user.passwordHash)) {
            user.copy(passwordHash = PasswordUtil.hash(password), updatedAt = System.currentTimeMillis()).also { users.update(it) }
        } else user
        Result.success(authenticated)
    }
}
