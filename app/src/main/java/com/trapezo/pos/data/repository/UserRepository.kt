package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.UserDao
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.utils.PasswordUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Admin-facing user management. Password hashes never leave this repository. */
class UserRepository(private val users: UserDao, private val settings: SettingsRepository) {
    data class SaveResult(val user: UserEntity? = null, val error: String? = null)

    suspend fun all(): List<UserEntity> = withContext(Dispatchers.IO) { users.all() }

    suspend fun save(
        existing: UserEntity?,
        username: String,
        displayName: String,
        role: String,
        password: String?,
        active: Boolean,
        actorId: Long
    ): SaveResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim()
        val cleanName = displayName.trim()
        if (cleanUsername.length < 3) return@withContext SaveResult(error = "Username minimal 3 karakter")
        if (cleanName.isBlank()) return@withContext SaveResult(error = "Nama pengguna wajib diisi")
        if (role !in setOf("ADMIN", "CASHIER")) return@withContext SaveResult(error = "Role tidak valid")
        val same = users.byUsername(cleanUsername)
        if (same != null && same.id != existing?.id) return@withContext SaveResult(error = "Username sudah digunakan")
        if (existing == null && (password == null || password.length < 6)) return@withContext SaveResult(error = "Password user baru minimal 6 karakter")
        if (existing?.id == actorId && !active) return@withContext SaveResult(error = "Anda tidak dapat menonaktifkan akun sendiri")

        val entity = UserEntity(
            id = existing?.id ?: 0,
            username = cleanUsername,
            passwordHash = if (password.isNullOrBlank()) existing!!.passwordHash else PasswordUtil.hash(password),
            name = cleanName,
            role = role,
            isActive = active,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val saved = if (existing == null) entity.copy(id = users.insert(entity)) else { users.update(entity); entity }
        settings.audit(actorId, if (existing == null) "USER_CREATE" else "USER_UPDATE", "user", saved.id, "${saved.username} (${saved.role})")
        SaveResult(user = saved)
    }

    suspend fun setActive(target: UserEntity, active: Boolean, actorId: Long): String? = withContext(Dispatchers.IO) {
        if (target.id == actorId && !active) return@withContext "Anda tidak dapat menonaktifkan akun sendiri"
        users.setActive(target.id, active)
        settings.audit(actorId, if (active) "USER_ACTIVATE" else "USER_DEACTIVATE", "user", target.id, target.username)
        null
    }
}
