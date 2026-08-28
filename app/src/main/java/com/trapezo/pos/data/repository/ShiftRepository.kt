package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import com.trapezo.pos.data.database.AppDatabase
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShiftRepository(private val db: AppDatabase) {
    sealed class Result {
        data class Ok(val shift: ShiftEntity) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun open(user: UserEntity, openingCash: Long): Result = withContext(Dispatchers.IO) {
        if (openingCash < 0) return@withContext Result.Error("Modal awal tidak boleh negatif")
        try {
            var opened: ShiftEntity? = null
            db.withTransaction {
                val actor = db.userDao().byId(user.id)
                    ?: throw IllegalArgumentException("Akun tidak ditemukan")
                if (!actor.isActive) throw IllegalArgumentException("Akun tidak aktif")
                db.shiftDao().anyOpenShift()?.let {
                    throw IllegalArgumentException("Masih ada shift aktif milik ${it.userNameSnapshot}; tutup shift tersebut terlebih dahulu")
                }
                val now = System.currentTimeMillis()
                val shift = ShiftEntity(
                    userId = actor.id,
                    userNameSnapshot = actor.name,
                    openingCash = openingCash,
                    expectedCash = openingCash,
                    openedAt = now,
                    status = "OPEN",
                    openGuard = 1
                )
                val id = db.shiftDao().openShift(shift)
                opened = shift.copy(id = id)
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = actor.id,
                        action = "SHIFT_OPEN",
                        referenceType = "shift",
                        referenceId = id,
                        description = "Modal awal Rp $openingCash",
                        createdAt = now
                    )
                )
            }
            Result.Ok(opened!!)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Gagal membuka shift")
        }
    }

    suspend fun cash(shift: ShiftEntity, type: String, amount: Long, note: String, userId: Long): Result =
        withContext(Dispatchers.IO) {
            if (amount <= 0) return@withContext Result.Error("Nominal harus lebih besar dari 0")
            if (type !in setOf("CASH_IN", "CASH_OUT")) return@withContext Result.Error("Tipe cash movement tidak valid")
            try {
                var updated: ShiftEntity? = null
                db.withTransaction {
                    val current = db.shiftDao().byId(shift.id)
                        ?: throw IllegalArgumentException("Shift tidak ditemukan")
                    if (current.status != "OPEN" || current.userId != userId) {
                        throw IllegalArgumentException("Shift ini tidak aktif atau bukan milik kasir")
                    }
                    val affected = if (type == "CASH_IN") {
                        db.shiftDao().addCashIn(current.id, userId, amount)
                    } else {
                        db.shiftDao().addCashOut(current.id, userId, amount)
                    }
                    if (affected != 1) {
                        if (type == "CASH_OUT") {
                            throw IllegalArgumentException("Cash out melebihi kas seharusnya atau shift berubah")
                        }
                        throw IllegalStateException("Shift berubah saat cash movement diproses")
                    }
                    val now = System.currentTimeMillis()
                    db.shiftDao().addCash(
                        CashMovementEntity(
                            shiftId = current.id,
                            type = type,
                            amount = amount,
                            note = note.trim(),
                            userId = userId,
                            createdAt = now
                        )
                    )
                    db.settingsDao().insertAudit(
                        AuditLogEntity(
                            userId = userId,
                            action = type,
                            referenceType = "shift",
                            referenceId = current.id,
                            description = "Rp $amount ${note.trim()}",
                            createdAt = now
                        )
                    )
                    updated = db.shiftDao().byId(current.id)
                }
                Result.Ok(updated!!)
            } catch (e: Exception) {
                Result.Error(e.message ?: "Cash movement gagal")
            }
        }

    suspend fun close(shift: ShiftEntity, actualCash: Long, userId: Long): Result = withContext(Dispatchers.IO) {
        if (actualCash < 0) return@withContext Result.Error("Kas aktual tidak boleh negatif")
        try {
            var closed: ShiftEntity? = null
            db.withTransaction {
                val latest = db.shiftDao().byId(shift.id)
                    ?: throw IllegalArgumentException("Shift tidak ditemukan")
                if (latest.status != "OPEN" || latest.userId != userId) {
                    throw IllegalArgumentException("Shift ini tidak aktif atau bukan milik kasir")
                }
                // expectedCash is the authoritative running balance maintained by sale/refund/cash writes.
                val expected = latest.expectedCash
                val difference = actualCash - expected
                val now = System.currentTimeMillis()
                if (db.shiftDao().closeShift(
                        id = latest.id,
                        totalCashSales = latest.totalCashSales,
                        nonCash = latest.totalNonCashSales,
                        expected = expected,
                        actual = actualCash,
                        diff = difference,
                        closedAt = now
                    ) != 1
                ) {
                    throw IllegalStateException("Shift berubah saat proses tutup; coba lagi")
                }
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = userId,
                        action = "SHIFT_CLOSE",
                        referenceType = "shift",
                        referenceId = latest.id,
                        description = "Kas aktual Rp $actualCash; selisih Rp $difference",
                        createdAt = now
                    )
                )
                closed = db.shiftDao().byId(latest.id)
            }
            Result.Ok(closed!!)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Gagal menutup shift")
        }
    }
}
