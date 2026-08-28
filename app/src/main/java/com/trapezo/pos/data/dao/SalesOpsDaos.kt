package com.trapezo.pos.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.SettingEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.UserEntity

data class DailyTotal(val dayStart: Long, val total: Long, val txCount: Int)
data class MethodTotal(val method: String, val total: Long, val txCount: Int)
data class TopProduct(val productId: Long?, val name: String, val qtySold: Long, val revenue: Long)
data class CashierTotal(val userName: String, val txCount: Int, val total: Long)
data class ItemSoldAgg(val totalQty: Double?, val lines: Int?)

@Dao
interface SaleDao {

    @Insert
    suspend fun insertSale(s: SaleEntity): Long

    @Insert
    suspend fun insertItems(items: List<SaleItemEntity>)

    @Insert
    suspend fun insertPayment(p: PaymentEntity): Long

    @Insert
    suspend fun insertPayments(ps: List<PaymentEntity>)

    @Transaction
    suspend fun saveFullSale(sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>): Long {
        val id = insertSale(sale)
        insertItems(items.map { it.copy(saleId = id) })
        if (payments.isNotEmpty()) insertPayments(payments.map { it.copy(saleId = id) })
        return id
    }

    @Query("UPDATE sales SET transactionStatus=:status WHERE id=:saleId")
    suspend fun setStatus(saleId: Long, status: String)

    @Query("SELECT COUNT(*) FROM sales WHERE invoiceNumber=:invoice")
    suspend fun invoiceExists(invoice: String): Int

    @Query("SELECT * FROM sales WHERE id=:id")
    suspend fun saleById(id: Long): SaleEntity?

    @Query("SELECT * FROM sales WHERE invoiceNumber=:invoice LIMIT 1")
    suspend fun saleByInvoice(invoice: String): SaleEntity?

    @Query("SELECT * FROM sale_items WHERE saleId=:saleId")
    suspend fun itemsFor(saleId: Long): List<SaleItemEntity>

    @Query("SELECT * FROM payments WHERE saleId=:saleId")
    suspend fun paymentsFor(saleId: Long): List<PaymentEntity>

    /** Dynamic filtered+paged history (filters built safely in repository with bound args). */
    @RawQuery(observedEntities = [SaleEntity::class])
    suspend fun historyRaw(query: SupportSQLiteQuery): List<SaleEntity>

    @RawQuery(observedEntities = [SaleEntity::class])
    suspend fun countRaw(query: SupportSQLiteQuery): Int

    // ---- aggregates ----
    @Query(
        """SELECT COALESCE(SUM(grandTotal),0) AS total, COUNT(*) AS cnt FROM sales
           WHERE createdAt BETWEEN :from AND :to AND transactionStatus != 'VOID'"""
    )
    suspend fun totalsBetween(from: Long, to: Long): TotalRow

    @Query(
        """SELECT CAST((createdAt/:dayMs)*:dayMs AS INTEGER) AS dayStart, SUM(grandTotal) AS total, COUNT(*) AS cnt
           FROM sales WHERE createdAt BETWEEN :from AND :to AND transactionStatus != 'VOID'
           GROUP BY dayStart ORDER BY dayStart ASC"""
    )
    suspend fun dailyTotals(from: Long, to: Long, dayMs: Long): List<DailyTotalRow>

    @Query(
        """SELECT p.method AS method, SUM(p.amount) AS total, COUNT(DISTINCT p.saleId) AS cnt
           FROM payments p JOIN sales s ON s.id = p.saleId
           WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
           GROUP BY p.method ORDER BY total DESC"""
    )
    suspend fun totalsByMethod(from: Long, to: Long): List<MethodTotalRow>

    @Query(
        """SELECT COALESCE(SUM(si.quantity),0) AS totalQty, COUNT(si.id) AS lines
           FROM sale_items si JOIN sales s ON s.id = si.saleId
           WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'"""
    )
    suspend fun itemsSoldBetween(from: Long, to: Long): ItemSoldAgg

    @Query(
        """SELECT si.productId AS productId, si.productNameSnapshot AS name,
                  SUM(si.quantity) AS qtySold, SUM(si.subtotal) AS revenue
           FROM sale_items si JOIN sales s ON s.id = si.saleId
           WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
           GROUP BY si.productId, si.productNameSnapshot
           ORDER BY qtySold DESC LIMIT :limit"""
    )
    suspend fun topProducts(from: Long, to: Long, limit: Int): List<TopProduct>

    @Query(
        """SELECT COALESCE(userNameSnapshot,'?') AS userName, COUNT(*) AS txCount, SUM(grandTotal) AS total
           FROM sales WHERE createdAt BETWEEN :from AND :to AND transactionStatus != 'VOID'
           GROUP BY userNameSnapshot ORDER BY total DESC"""
    )
    suspend fun totalsByCashier(from: Long, to: Long): List<CashierTotal>

    data class TotalRow(val total: Long, val cnt: Int)
    data class DailyTotalRow(val dayStart: Long, val total: Long?, val cnt: Int)
    data class MethodTotalRow(val method: String, val total: Long?, val cnt: Int)
}

@Dao
interface RefundDao {
    @androidx.room.Insert
    suspend fun insertRefund(r: com.trapezo.pos.data.entity.RefundEntity): Long

    @androidx.room.Insert
    suspend fun insertRefundItems(items: List<com.trapezo.pos.data.entity.RefundItemEntity>)

    @Query("SELECT * FROM refunds WHERE saleId=:saleId")
    suspend fun refundsForSale(saleId: Long): List<com.trapezo.pos.data.entity.RefundEntity>

    @Query("SELECT * FROM refund_items WHERE refundId=:refundId")
    suspend fun itemsFor(refundId: Long): List<com.trapezo.pos.data.entity.RefundItemEntity>

    @Query("""SELECT COALESCE(SUM(ri.quantity),0) FROM refund_items ri
              JOIN refunds r ON r.id = ri.refundId WHERE ri.saleItemId=:saleItemId""")
    suspend fun refundedQtyFor(saleItemId: Long): Long

    @Query("SELECT COALESCE(SUM(total),0) FROM refunds WHERE saleId=:saleId")
    suspend fun refundedTotalFor(saleId: Long): Long
}

@Dao
interface ShiftDao {

    @Insert
    suspend fun openShift(s: ShiftEntity): Long

    @Query("UPDATE shifts SET totalCashSales=:totalCashSales, totalNonCashSales=:nonCash, expectedCash=:expected, actualCash=:actual, difference=:diff, closedAt=:closedAt, status='CLOSED' WHERE id=:id")
    suspend fun closeShift(id: Long, totalCashSales: Long, nonCash: Long, expected: Long, actual: Long, diff: Long, closedAt: Long)

    @Query("SELECT * FROM shifts WHERE status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    suspend fun anyOpenShift(): ShiftEntity?

    @Query(
        """UPDATE shifts SET totalCashSales = totalCashSales + :cashAmt,
           totalNonCashSales = totalNonCashSales + :nonCashAmt,
           expectedCash = expectedCash + :cashAmt WHERE id=:id"""
    )
    suspend fun addSaleTotals(id: Long, cashAmt: Long, nonCashAmt: Long)

    @Query("SELECT * FROM shifts WHERE userId=:userId AND status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    suspend fun openShiftForUser(userId: Long): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    fun openShiftFlow(): kotlinx.coroutines.flow.Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE id=:id")
    suspend fun byId(id: Long): ShiftEntity?

    @Insert
    suspend fun addCash(m: CashMovementEntity): Long

    @Query("SELECT * FROM cash_movements WHERE shiftId=:shiftId ORDER BY createdAt ASC")
    suspend fun cashMovements(shiftId: Long): List<CashMovementEntity>

    @Query("SELECT * FROM shifts ORDER BY openedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun allShifts(limit: Int, offset: Int): List<ShiftEntity>

    @Query("SELECT * FROM cash_movements ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun allCashMovements(limit: Int, offset: Int): List<CashMovementEntity>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key`=:key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("DELETE FROM settings WHERE `key`=:key")
    suspend fun remove(key: String)

    @Insert
    suspend fun insertAudit(a: AuditLogEntity): Long

    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun audits(limit: Int, offset: Int): List<AuditLogEntity>
}
