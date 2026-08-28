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
import com.trapezo.pos.data.entity.RefundPaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.SettingEntity
import com.trapezo.pos.data.entity.ShiftEntity

data class DailyTotal(val dayStart: Long, val total: Long, val txCount: Int)
data class MethodTotal(val method: String, val total: Long, val txCount: Int)
data class TopProduct(val productId: Long?, val name: String, val qtySold: Long, val revenue: Long)
data class CashierTotal(val userName: String, val txCount: Int, val total: Long)
data class ItemSoldAgg(val totalQty: Double?, val lines: Int?)
data class RefundMethodTotal(val method: String, val total: Long)

@Dao
interface SaleDao {
    @Insert suspend fun insertSale(s: SaleEntity): Long
    @Insert suspend fun insertItems(items: List<SaleItemEntity>)
    @Insert suspend fun insertPayment(p: PaymentEntity): Long
    @Insert suspend fun insertPayments(ps: List<PaymentEntity>)

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

    @Query("SELECT * FROM sale_items WHERE saleId=:saleId ORDER BY id ASC")
    suspend fun itemsFor(saleId: Long): List<SaleItemEntity>

    @Query("SELECT * FROM payments WHERE saleId=:saleId ORDER BY id ASC")
    suspend fun paymentsFor(saleId: Long): List<PaymentEntity>

    @RawQuery(observedEntities = [SaleEntity::class])
    suspend fun historyRaw(query: SupportSQLiteQuery): List<SaleEntity>

    @RawQuery(observedEntities = [SaleEntity::class])
    suspend fun countRaw(query: SupportSQLiteQuery): Int

    @Query(
        """SELECT
             COALESCE((SELECT SUM(s.grandTotal) FROM sales s
                       WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'), 0)
             - COALESCE((SELECT SUM(r.total) FROM refunds r
                         WHERE r.createdAt BETWEEN :from AND :to), 0) AS total,
             (SELECT COUNT(*) FROM sales s2
              WHERE s2.createdAt BETWEEN :from AND :to AND s2.transactionStatus != 'VOID') AS cnt"""
    )
    suspend fun totalsBetween(from: Long, to: Long): TotalRow

    @Query(
        """SELECT method, SUM(amount) AS total, CAST(SUM(saleCount) AS INTEGER) AS cnt
           FROM (
               SELECT p.method AS method, SUM(p.amount) AS amount,
                      COUNT(DISTINCT p.saleId) AS saleCount
               FROM payments p JOIN sales s ON s.id = p.saleId
               WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
               GROUP BY p.method
               UNION ALL
               SELECT rp.method AS method, -SUM(rp.amount) AS amount, 0 AS saleCount
               FROM refund_payments rp JOIN refunds r ON r.id = rp.refundId
               WHERE r.createdAt BETWEEN :from AND :to
               GROUP BY rp.method
           ) movements
           GROUP BY method ORDER BY total DESC"""
    )
    suspend fun totalsByMethod(from: Long, to: Long): List<MethodTotalRow>

    @Query(
        """SELECT COALESCE(SUM(qty),0) AS totalQty, CAST(COALESCE(SUM(lineCount),0) AS INTEGER) AS lines
           FROM (
               SELECT SUM(si.quantity) AS qty, COUNT(si.id) AS lineCount
               FROM sale_items si JOIN sales s ON s.id = si.saleId
               WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
               UNION ALL
               SELECT -SUM(ri.quantity) AS qty, -COUNT(ri.id) AS lineCount
               FROM refund_items ri JOIN refunds r ON r.id = ri.refundId
               WHERE r.createdAt BETWEEN :from AND :to
           ) movement"""
    )
    suspend fun itemsSoldBetween(from: Long, to: Long): ItemSoldAgg

    @Query(
        """SELECT productId, name, SUM(qty) AS qtySold, SUM(revenue) AS revenue
           FROM (
               SELECT si.productId AS productId, si.productNameSnapshot AS name,
                      si.quantity AS qty, si.netTotal AS revenue
               FROM sale_items si JOIN sales s ON s.id = si.saleId
               WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
               UNION ALL
               SELECT ri.productId AS productId, si.productNameSnapshot AS name,
                      -ri.quantity AS qty, -ri.amount AS revenue
               FROM refund_items ri
               JOIN refunds r ON r.id = ri.refundId
               JOIN sale_items si ON si.id = ri.saleItemId
               WHERE r.createdAt BETWEEN :from AND :to
           ) productMovement
           GROUP BY productId, name
           ORDER BY qtySold DESC, revenue DESC LIMIT :limit"""
    )
    suspend fun topProducts(from: Long, to: Long, limit: Int): List<TopProduct>

    @Query(
        """SELECT userName, CAST(SUM(txCount) AS INTEGER) AS txCount, SUM(total) AS total
           FROM (
               SELECT COALESCE(s.userNameSnapshot,'?') AS userName,
                      COUNT(*) AS txCount, SUM(s.grandTotal) AS total
               FROM sales s
               WHERE s.createdAt BETWEEN :from AND :to AND s.transactionStatus != 'VOID'
               GROUP BY s.userNameSnapshot
               UNION ALL
               SELECT COALESCE(s.userNameSnapshot,'?') AS userName,
                      0 AS txCount, -SUM(r.total) AS total
               FROM refunds r JOIN sales s ON s.id = r.saleId
               WHERE r.createdAt BETWEEN :from AND :to
               GROUP BY s.userNameSnapshot
           ) cashierMovement
           GROUP BY userName ORDER BY total DESC"""
    )
    suspend fun totalsByCashier(from: Long, to: Long): List<CashierTotal>

    data class TotalRow(val total: Long, val cnt: Int)
    data class DailyTotalRow(val dayStart: Long, val total: Long?, val cnt: Int)
    data class MethodTotalRow(val method: String, val total: Long?, val cnt: Int)
}

@Dao
interface RefundDao {
    @Insert suspend fun insertRefund(r: com.trapezo.pos.data.entity.RefundEntity): Long
    @Insert suspend fun insertRefundItems(items: List<com.trapezo.pos.data.entity.RefundItemEntity>)
    @Insert suspend fun insertRefundPayments(items: List<RefundPaymentEntity>)

    @Query("SELECT * FROM refunds WHERE saleId=:saleId ORDER BY id ASC")
    suspend fun refundsForSale(saleId: Long): List<com.trapezo.pos.data.entity.RefundEntity>

    @Query("SELECT * FROM refund_items WHERE refundId=:refundId ORDER BY id ASC")
    suspend fun itemsFor(refundId: Long): List<com.trapezo.pos.data.entity.RefundItemEntity>

    @Query("SELECT * FROM refund_payments WHERE refundId=:refundId ORDER BY id ASC")
    suspend fun paymentsFor(refundId: Long): List<RefundPaymentEntity>

    @Query("""SELECT COALESCE(SUM(ri.quantity),0) FROM refund_items ri
              JOIN refunds r ON r.id = ri.refundId WHERE ri.saleItemId=:saleItemId""")
    suspend fun refundedQtyFor(saleItemId: Long): Long

    @Query("""SELECT COALESCE(SUM(ri.amount),0) FROM refund_items ri
              JOIN refunds r ON r.id = ri.refundId WHERE ri.saleItemId=:saleItemId""")
    suspend fun refundedAmountFor(saleItemId: Long): Long

    @Query("SELECT COALESCE(SUM(total),0) FROM refunds WHERE saleId=:saleId")
    suspend fun refundedTotalFor(saleId: Long): Long

    @Query(
        """SELECT rp.method AS method, COALESCE(SUM(rp.amount),0) AS total
           FROM refund_payments rp JOIN refunds r ON r.id = rp.refundId
           WHERE r.saleId=:saleId GROUP BY rp.method ORDER BY MIN(rp.id) ASC"""
    )
    suspend fun refundedMethodTotalsFor(saleId: Long): List<RefundMethodTotal>
}

@Dao
interface ShiftDao {
    @Insert suspend fun openShift(s: ShiftEntity): Long

    @Query(
        """UPDATE shifts SET totalCashSales=:totalCashSales, totalNonCashSales=:nonCash,
           expectedCash=:expected, actualCash=:actual, difference=:diff, closedAt=:closedAt,
           status='CLOSED', openGuard=NULL WHERE id=:id AND status='OPEN'"""
    )
    suspend fun closeShift(
        id: Long,
        totalCashSales: Long,
        nonCash: Long,
        expected: Long,
        actual: Long,
        diff: Long,
        closedAt: Long
    ): Int

    @Query("SELECT * FROM shifts WHERE status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    suspend fun anyOpenShift(): ShiftEntity?

    @Query(
        """UPDATE shifts SET totalCashSales = totalCashSales + :cashAmt,
           totalNonCashSales = totalNonCashSales + :nonCashAmt,
           expectedCash = expectedCash + :cashAmt WHERE id=:id AND status='OPEN'"""
    )
    suspend fun addSaleTotals(id: Long, cashAmt: Long, nonCashAmt: Long): Int

    @Query(
        """UPDATE shifts SET totalCashSales = totalCashSales - :cashAmt,
           totalNonCashSales = totalNonCashSales - :nonCashAmt,
           expectedCash = expectedCash - :cashAmt
           WHERE id=:id AND status='OPEN'"""
    )
    suspend fun addRefundTotals(id: Long, cashAmt: Long, nonCashAmt: Long): Int

    @Query(
        """UPDATE shifts SET cashIn=cashIn+:amount, expectedCash=expectedCash+:amount
           WHERE id=:id AND status='OPEN' AND userId=:userId"""
    )
    suspend fun addCashIn(id: Long, userId: Long, amount: Long): Int

    @Query(
        """UPDATE shifts SET cashOut=cashOut+:amount, expectedCash=expectedCash-:amount
           WHERE id=:id AND status='OPEN' AND userId=:userId AND expectedCash>=:amount"""
    )
    suspend fun addCashOut(id: Long, userId: Long, amount: Long): Int

    @Query("SELECT * FROM shifts WHERE userId=:userId AND status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    suspend fun openShiftForUser(userId: Long): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE status='OPEN' ORDER BY openedAt DESC LIMIT 1")
    fun openShiftFlow(): kotlinx.coroutines.flow.Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE id=:id")
    suspend fun byId(id: Long): ShiftEntity?

    @Insert suspend fun addCash(m: CashMovementEntity): Long

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

    @Insert suspend fun insertAudit(a: AuditLogEntity): Long

    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun audits(limit: Int, offset: Int): List<AuditLogEntity>
}
