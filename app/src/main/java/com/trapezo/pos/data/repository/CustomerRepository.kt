package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.dao.CustomerDao
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.CustomerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomerRepository(
    private val db: AppDatabase,
    private val dao: CustomerDao,
    private val settings: SettingsRepository
) {
    data class SaveResult(val customer: CustomerEntity? = null, val error: String? = null)

    suspend fun page(query: String, page: Int = 0, pageSize: Int = 50): Pair<List<CustomerEntity>, Int> = withContext(Dispatchers.IO) {
        Pair(dao.page(query.trim(), pageSize, page * pageSize), dao.count(query.trim()))
    }

    /** Customer master-data edits are admin-only. Points/balance are ledger-owned and immutable here. */
    suspend fun save(customer: CustomerEntity, actorId: Long): SaveResult = withContext(Dispatchers.IO) {
        if (customer.name.trim().isEmpty()) return@withContext SaveResult(error = "Nama customer wajib diisi")
        try {
            var saved: CustomerEntity? = null
            db.withTransaction {
                val actor = db.userDao().byId(actorId)
                    ?: throw IllegalArgumentException("Akun admin tidak ditemukan")
                if (!actor.isActive || actor.role != "ADMIN") {
                    throw IllegalArgumentException("Hanya admin aktif yang dapat mengubah data customer")
                }

                val existing = if (customer.id == 0L) null else dao.byId(customer.id)
                    ?: throw IllegalArgumentException("Customer tidak ditemukan")
                var value = customer.copy(
                    name = customer.name.trim(),
                    phone = customer.phone.trim(),
                    email = customer.email.trim(),
                    address = customer.address.trim(),
                    // Financial loyalty fields may only change through their own future ledgers.
                    points = existing?.points ?: 0,
                    balance = existing?.balance ?: 0
                )
                if (value.code.isBlank()) {
                    value = value.copy(code = settings.reserveNextCustomerCode())
                }
                val same = dao.byCode(value.code)
                if (same != null && same.id != value.id) {
                    throw IllegalArgumentException("Kode customer sudah digunakan")
                }

                val now = System.currentTimeMillis()
                saved = if (existing == null) {
                    val newValue = value.copy(createdAt = now, updatedAt = now)
                    newValue.copy(id = dao.insert(newValue))
                } else {
                    val updated = value.copy(createdAt = existing.createdAt, updatedAt = now)
                    dao.update(updated)
                    updated
                }
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actorId,
                        action = if (existing == null) "CUSTOMER_CREATE" else "CUSTOMER_UPDATE",
                        referenceType = "customer",
                        referenceId = saved!!.id,
                        description = saved!!.name,
                        createdAt = now
                    )
                )
            }
            SaveResult(customer = saved)
        } catch (e: Exception) {
            SaveResult(error = e.message ?: "Gagal menyimpan customer")
        }
    }

    suspend fun delete(customer: CustomerEntity, actorId: Long): String? = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                val actor = db.userDao().byId(actorId)
                    ?: throw IllegalArgumentException("Akun admin tidak ditemukan")
                if (!actor.isActive || actor.role != "ADMIN") {
                    throw IllegalArgumentException("Hanya admin aktif yang dapat menghapus customer")
                }
                dao.delete(customer.id)
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actorId,
                        action = "CUSTOMER_DELETE",
                        referenceType = "customer",
                        referenceId = customer.id,
                        description = customer.name
                    )
                )
            }
            null
        } catch (e: Exception) {
            e.message ?: "Gagal menghapus customer"
        }
    }
}
