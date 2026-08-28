package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.dao.UserDao
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.utils.PasswordUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Admin-facing user management. Password hashes never leave this repository. */
class UserRepository(
    private val db: AppDatabase,
    private val users: UserDao,
    private val settings: SettingsRepository
) {
    data class SaveResult(val user: UserEntity? = null, val error: String? = null)

    suspend fun all(): List<UserEntity> = withContext(Dispatchers.IO) { users.all() }
    suspend fun hasUsers(): Boolean = withContext(Dispatchers.IO) { users.count() > 0 }

    /** Creates the only first-run owner account. It is impossible once any user exists. */
    suspend fun bootstrapAdmin(
        username: String,
        displayName: String,
        password: String
    ): SaveResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim()
        val cleanName = displayName.trim()
        if (cleanUsername.length < 3) return@withContext SaveResult(error = "Username minimal 3 karakter")
        if (cleanName.isBlank()) return@withContext SaveResult(error = "Nama pemilik wajib diisi")
        if (password.length < 8) return@withContext SaveResult(error = "Password minimal 8 karakter")

        try {
            var saved: UserEntity? = null
            db.withTransaction {
                check(users.count() == 0) { "Setup awal sudah pernah dilakukan" }
                val now = System.currentTimeMillis()
                val entity = UserEntity(
                    username = cleanUsername,
                    passwordHash = PasswordUtil.hash(password),
                    name = cleanName,
                    role = "ADMIN",
                    isActive = true,
                    createdAt = now,
                    updatedAt = now
                )
                val id = users.insert(entity)
                saved = entity.copy(id = id)
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = id,
                        action = "OWNER_BOOTSTRAP",
                        referenceType = "user",
                        referenceId = id,
                        description = cleanUsername,
                        createdAt = now
                    )
                )
            }
            SaveResult(user = saved)
        } catch (e: Exception) {
            SaveResult(error = e.message ?: "Gagal membuat akun pemilik")
        }
    }

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
        if (existing == null && (password == null || password.length < 8)) {
            return@withContext SaveResult(error = "Password user baru minimal 8 karakter")
        }
        if (!password.isNullOrBlank() && password.length < 8) {
            return@withContext SaveResult(error = "Password minimal 8 karakter")
        }

        try {
            var saved: UserEntity? = null
            db.withTransaction {
                val actor = users.byId(actorId)
                    ?: throw IllegalArgumentException("Akun admin tidak ditemukan")
                if (!actor.isActive || actor.role != "ADMIN") {
                    throw IllegalArgumentException("Hanya admin aktif yang dapat mengelola user")
                }

                val same = users.byUsername(cleanUsername)
                if (same != null && same.id != existing?.id) {
                    throw IllegalArgumentException("Username sudah digunakan")
                }

                val authoritativeExisting = existing?.let {
                    users.byId(it.id) ?: throw IllegalArgumentException("User tidak ditemukan")
                }
                if (authoritativeExisting?.id == actorId && !active) {
                    throw IllegalArgumentException("Anda tidak dapat menonaktifkan akun sendiri")
                }

                val removesActiveAdmin = authoritativeExisting?.let {
                    it.isActive && it.role == "ADMIN" && (!active || role != "ADMIN")
                } == true
                if (removesActiveAdmin && users.countActiveAdmins() <= 1) {
                    throw IllegalArgumentException("Minimal satu admin aktif harus tetap tersedia")
                }

                val now = System.currentTimeMillis()
                val entity = UserEntity(
                    id = authoritativeExisting?.id ?: 0,
                    username = cleanUsername,
                    passwordHash = when {
                        !password.isNullOrBlank() -> PasswordUtil.hash(password)
                        authoritativeExisting != null -> authoritativeExisting.passwordHash
                        else -> throw IllegalArgumentException("Password wajib diisi")
                    },
                    name = cleanName,
                    role = role,
                    isActive = active,
                    failedLoginCount = if (!password.isNullOrBlank()) 0 else authoritativeExisting?.failedLoginCount ?: 0,
                    lockedUntil = if (!password.isNullOrBlank()) 0 else authoritativeExisting?.lockedUntil ?: 0,
                    createdAt = authoritativeExisting?.createdAt ?: now,
                    updatedAt = now
                )
                saved = if (authoritativeExisting == null) {
                    entity.copy(id = users.insert(entity))
                } else {
                    users.update(entity)
                    entity
                }
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actorId,
                        action = if (authoritativeExisting == null) "USER_CREATE" else "USER_UPDATE",
                        referenceType = "user",
                        referenceId = saved!!.id,
                        description = "${saved!!.username} (${saved!!.role})",
                        createdAt = now
                    )
                )
            }
            SaveResult(user = saved)
        } catch (e: Exception) {
            SaveResult(error = e.message ?: "Gagal menyimpan user")
        }
    }

    suspend fun setActive(target: UserEntity, active: Boolean, actorId: Long): String? = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                val actor = users.byId(actorId)
                    ?: throw IllegalArgumentException("Akun admin tidak ditemukan")
                if (!actor.isActive || actor.role != "ADMIN") {
                    throw IllegalArgumentException("Hanya admin aktif yang dapat mengelola user")
                }
                val current = users.byId(target.id) ?: throw IllegalArgumentException("User tidak ditemukan")
                if (current.id == actorId && !active) {
                    throw IllegalArgumentException("Anda tidak dapat menonaktifkan akun sendiri")
                }
                if (current.isActive && current.role == "ADMIN" && !active && users.countActiveAdmins() <= 1) {
                    throw IllegalArgumentException("Minimal satu admin aktif harus tetap tersedia")
                }
                users.setActive(current.id, active)
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actorId,
                        action = if (active) "USER_ACTIVATE" else "USER_DEACTIVATE",
                        referenceType = "user",
                        referenceId = current.id,
                        description = current.username
                    )
                )
            }
            null
        } catch (e: Exception) {
            e.message ?: "Gagal mengubah status user"
        }
    }
}
