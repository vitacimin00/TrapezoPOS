package com.trapezo.pos.data.repository

import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.SettingEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Key-value settings backed by the settings table, with typed accessors.
 *
 * Two write layers:
 *  - plain [put]/[putLong] are internal counters (invoice/sku/customer sequences)
 *    and must never be used for admin configuration from the UI;
 *  - [putSetting]/[putLongSetting]/[setPaymentMethodActive] require an active ADMIN
 *    actor and are the only way administrative configuration may be mutated.
 */
class SettingsRepository(private val db: AppDatabase) {

    private val dao get() = db.settingsDao()

    suspend fun raw(key: String, def: String = ""): String = dao.get(key) ?: def

    /** Internal counter/setting write — do NOT use for admin-visible configuration. */
    suspend fun put(key: String, value: String) {
        if (value.isEmpty()) dao.remove(key) else dao.put(SettingEntity(key = key, value = value))
    }

    /** Internal counter write — do NOT use for admin-visible configuration. */
    suspend fun putLong(key: String, v: Long) = put(key, v.toString())

    /** Admin-only configuration write. */
    suspend fun putSetting(key: String, value: String, actorId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            Authorization.requireActiveAdmin(db, actorId)
            if (value.isEmpty()) dao.remove(key) else dao.put(SettingEntity(key = key, value = value))
        }
    }

    /** Admin-only numeric configuration write. */
    suspend fun putLongSetting(key: String, v: Long, actorId: Long) = putSetting(key, v.toString(), actorId)

    /** Admin-only payment-method enable/disable. Historical payment rows remain untouched. */
    suspend fun setPaymentMethodActive(type: String, active: Boolean, actorId: Long): String? = withContext(Dispatchers.IO) {
        try {
            db.withTransaction {
                Authorization.requireActiveAdmin(db, actorId)
                val method = db.paymentMethodDao().byType(type)
                    ?: throw IllegalArgumentException("Metode pembayaran tidak dikenal")
                db.paymentMethodDao().setActive(method.id, active)
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actorId,
                        action = if (active) "PAYMENT_METHOD_ACTIVATE" else "PAYMENT_METHOD_DEACTIVATE",
                        referenceType = "payment_method",
                        referenceId = method.id,
                        description = method.name.ifBlank { type }
                    )
                )
            }
            null
        } catch (e: Exception) {
            e.message ?: "Gagal mengubah metode pembayaran"
        }
    }

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
