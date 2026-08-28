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
    sealed class Result { data class Ok(val shift: ShiftEntity): Result(); data class Error(val message:String): Result() }
    suspend fun open(user: UserEntity, openingCash: Long): Result = withContext(Dispatchers.IO) {
        if (openingCash < 0) return@withContext Result.Error("Modal awal tidak boleh negatif")
        db.shiftDao().anyOpenShift()?.let {
            return@withContext Result.Error("Masih ada shift aktif milik ${it.userNameSnapshot}; tutup shift tersebut terlebih dahulu")
        }
        val s = ShiftEntity(userId=user.id,userNameSnapshot=user.name,openingCash=openingCash,expectedCash=openingCash)
        val id=db.shiftDao().openShift(s)
        db.settingsDao().insertAudit(AuditLogEntity(userId = user.id, action = "SHIFT_OPEN", referenceType = "shift", referenceId = id, description = "Modal awal Rp $openingCash"))
        Result.Ok(s.copy(id=id))
    }
    suspend fun cash(shift: ShiftEntity, type: String, amount:Long, note:String, userId:Long): Result = withContext(Dispatchers.IO) {
        if (amount <= 0) return@withContext Result.Error("Nominal harus lebih besar dari 0")
        if (type !in setOf("CASH_IN", "CASH_OUT")) return@withContext Result.Error("Tipe cash movement tidak valid")
        val current = db.shiftDao().byId(shift.id) ?: return@withContext Result.Error("Shift tidak ditemukan")
        if (current.status != "OPEN" || current.userId != userId) return@withContext Result.Error("Shift ini tidak aktif atau bukan milik kasir")
        if (type == "CASH_OUT" && amount > current.expectedCash) {
            return@withContext Result.Error("Cash out melebihi kas seharusnya (${current.expectedCash})")
        }
        db.withTransaction {
            db.shiftDao().addCash(CashMovementEntity(shiftId = shift.id, type = type, amount = amount, note = note.trim(), userId = userId))
            db.openHelper.writableDatabase.execSQL(if(type=="CASH_IN") "UPDATE shifts SET cashIn=cashIn+?, expectedCash=expectedCash+? WHERE id=?" else "UPDATE shifts SET cashOut=cashOut+?, expectedCash=expectedCash-? WHERE id=?", arrayOf(amount,amount,shift.id))
        }
        Result.Ok(db.shiftDao().byId(shift.id)!!)
    }
    suspend fun close(shift: ShiftEntity, actualCash:Long, userId:Long): Result = withContext(Dispatchers.IO) {
        if(actualCash<0) return@withContext Result.Error("Kas aktual tidak boleh negatif")
        val latest = db.shiftDao().byId(shift.id) ?: return@withContext Result.Error("Shift tidak ditemukan")
        if (latest.status != "OPEN" || latest.userId != userId) return@withContext Result.Error("Shift ini tidak aktif atau bukan milik kasir")
        val expected=latest.openingCash+latest.totalCashSales+latest.cashIn-latest.cashOut
        val difference=actualCash-expected
        db.shiftDao().closeShift(shift.id,latest.totalCashSales,latest.totalNonCashSales,expected,actualCash,difference,System.currentTimeMillis())
        db.settingsDao().insertAudit(AuditLogEntity(userId = userId, action = "SHIFT_CLOSE", referenceType = "shift", referenceId = shift.id, description = "Kas aktual Rp $actualCash; selisih Rp $difference"))
        Result.Ok(db.shiftDao().byId(shift.id)!!)
    }
}
