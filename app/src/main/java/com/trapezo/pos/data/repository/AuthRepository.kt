package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.UserDao
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.utils.PasswordUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class AuthRepository(private val users: UserDao) {
    suspend fun login(username: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Username dan password wajib diisi"))
        }

        val now = System.currentTimeMillis()
        val user = users.byUsername(username.trim())
            ?: return@withContext Result.failure(IllegalArgumentException("Username atau password salah"))
        if (!user.isActive) {
            return@withContext Result.failure(IllegalArgumentException("Akun ini tidak aktif"))
        }
        if (user.lockedUntil > now) {
            val seconds = ceil((user.lockedUntil - now) / 1000.0).toLong().coerceAtLeast(1)
            return@withContext Result.failure(
                IllegalArgumentException("Terlalu banyak percobaan login. Coba lagi dalam $seconds detik")
            )
        }

        if (!PasswordUtil.verify(password, user.passwordHash)) {
            val failures = user.failedLoginCount + 1
            val cooldownMs = when {
                failures <= 3 -> 0L
                failures == 4 -> 15_000L
                failures == 5 -> 30_000L
                failures == 6 -> 60_000L
                failures == 7 -> 5 * 60_000L
                else -> 15 * 60_000L
            }
            users.recordFailedLogin(user.id, failures, if (cooldownMs > 0) now + cooldownMs else 0L, now)
            return@withContext Result.failure(IllegalArgumentException("Username atau password salah"))
        }

        var authenticated = user
        if (PasswordUtil.needsRehash(user.passwordHash)) {
            authenticated = user.copy(
                passwordHash = PasswordUtil.hash(password),
                failedLoginCount = 0,
                lockedUntil = 0,
                updatedAt = now
            )
            users.update(authenticated)
        } else if (user.failedLoginCount != 0 || user.lockedUntil != 0L) {
            users.clearLoginFailures(user.id, now)
            authenticated = user.copy(failedLoginCount = 0, lockedUntil = 0, updatedAt = now)
        }
        Result.success(authenticated)
    }
}
