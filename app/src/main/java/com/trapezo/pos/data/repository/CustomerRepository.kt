package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.CustomerDao
import com.trapezo.pos.data.entity.CustomerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomerRepository(
    private val dao: CustomerDao,
    private val settings: SettingsRepository
) {
    data class SaveResult(val customer: CustomerEntity? = null, val error: String? = null)

    suspend fun page(query: String, page: Int = 0, pageSize: Int = 50): Pair<List<CustomerEntity>, Int> = withContext(Dispatchers.IO) {
        Pair(dao.page(query.trim(), pageSize, page * pageSize), dao.count(query.trim()))
    }

    suspend fun save(customer: CustomerEntity): SaveResult = withContext(Dispatchers.IO) {
        if (customer.name.trim().isEmpty()) return@withContext SaveResult(error = "Nama customer wajib diisi")
        var value = customer.copy(name = customer.name.trim(), phone = customer.phone.trim(), email = customer.email.trim(), address = customer.address.trim())
        if (value.code.isBlank()) {
            val seq = settings.long("customer.seq", 1)
            value = value.copy(code = "CUS-%06d".format(seq))
            settings.putLong("customer.seq", seq + 1)
        }
        val same = dao.byCode(value.code)
        if (same != null && same.id != value.id) return@withContext SaveResult(error = "Kode customer sudah digunakan")
        val saved = if (value.id == 0L) {
            val id = dao.insert(value)
            value.copy(id = id)
        } else {
            val updated = value.copy(updatedAt = System.currentTimeMillis())
            dao.update(updated); updated
        }
        settings.audit(null, if (customer.id == 0L) "CUSTOMER_CREATE" else "CUSTOMER_UPDATE", "customer", saved.id, saved.name)
        SaveResult(customer = saved)
    }

    suspend fun delete(customer: CustomerEntity) = withContext(Dispatchers.IO) {
        dao.delete(customer.id)
        settings.audit(null, "CUSTOMER_DELETE", "customer", customer.id, customer.name)
    }
}
