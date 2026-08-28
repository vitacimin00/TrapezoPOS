package com.trapezo.pos.data.repository

import com.trapezo.pos.data.dao.SettingsDao
import com.trapezo.pos.data.entity.AuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Key-value settings backed by the settings table, with typed accessors.
 */
class SettingsRepository(private val dao: SettingsDao) {

    suspend fun raw(key: String, def: String = ""): String = dao.get(key) ?: def

    suspend fun put(key: String, value: String) {
        if (value.isEmpty()) dao.remove(key) else dao.put(com.trapezo.pos.data.entity.SettingEntity(key = key, value = value))
    }

    suspend fun putLong(key: String, v: Long) = put(key, v.toString())
    suspend fun long(key: String, def: Long): Long = raw(key, def.toString()).toLongOrNull() ?: def
    suspend fun bool(key: String, def: Boolean): Boolean =
        when (raw(key, if (def) "1" else "0")) { "1", "true" -> true; else -> false }

    // ---- typed helpers ----
    suspend fun taxPercent(): Long = long("pos.tax_percent", 0)
    suspend fun servicePercent(): Long = long("pos.service_percent", 0)
    suspend fun rounding(): Long = long("pos.rounding", 0)
    suspend fun invoicePrefix(): String = raw("pos.invoice_prefix", "INV")
    suspend fun invoiceSeq(): Long = long("pos.invoice_seq", 1)

    suspend fun nextInvoiceNumber(): String {
        val seq = long("pos.invoice_seq", 1)
        val num = String.format("%06d", seq)
        val ymd = com.trapezo.pos.utils.Dates.ymd(System.currentTimeMillis()).replace("-", "")
        putLong("pos.invoice_seq", seq + 1)
        return "${invoicePrefix()}-$ymd-$num"
    }

    /** Peek the next invoice number without consuming the sequence (POS header display). */
    suspend fun peekInvoiceNumber(): String {
        val seq = long("pos.invoice_seq", 1)
        val ymd = com.trapezo.pos.utils.Dates.ymd(System.currentTimeMillis()).replace("-", "")
        return "${invoicePrefix()}-$ymd-${String.format("%06d", seq)}"
    }

    suspend fun audit(userId: Long?, action: String, refType: String, refId: Long?, desc: String) {
        withContext(Dispatchers.IO) {
            dao.insertAudit(
                AuditLogEntity(
                    userId = userId, action = action,
                    referenceType = refType, referenceId = refId,
                    description = desc
                )
            )
        }
    }
}
