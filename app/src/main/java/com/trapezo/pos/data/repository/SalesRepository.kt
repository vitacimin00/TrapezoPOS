package com.trapezo.pos.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.entity.AuditLogEntity
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.OrderDiscount
import com.trapezo.pos.domain.model.PaymentAllocation
import com.trapezo.pos.domain.model.PricingEngine
import com.trapezo.pos.domain.model.Totals
import com.trapezo.pos.utils.Dates
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compatibility facade used by the current POS UI. */
object PriceEngine {
    fun totals(
        lines: List<CartLine>,
        discount: OrderDiscount,
        taxPercent: Long,
        servicePercent: Long,
        roundingStep: Long
    ): Totals = PricingEngine.price(lines, discount, taxPercent, servicePercent, roundingStep).totals
}

/** Sales repository: history queries and the atomic checkout transaction. */
class SalesRepository(
    private val db: com.trapezo.pos.data.database.AppDatabase,
    private val saleDao: SaleDao,
    private val settings: SettingsRepository
) {

    sealed class CheckoutResult {
        data class Success(
            val sale: SaleEntity,
            val items: List<SaleItemEntity>,
            val invoice: String
        ) : CheckoutResult()

        data class Failure(val error: String) : CheckoutResult()
    }

    private class StockException(m: String) : RuntimeException(m)

    /**
     * Atomic checkout: validates stock, writes sale+items+payments, decrements stock,
     * updates shift cash counters and writes an audit row — all in ONE database transaction.
     * Each sale item also stores its exact final net share after discount/tax/service/rounding,
     * which is the only monetary base later used by refunds.
     */
    suspend fun checkout(
        lines: List<CartLine>,
        discount: OrderDiscount,
        paidByMethod: Map<String, Long>,
        references: Map<String, String>,
        user: com.trapezo.pos.data.entity.UserEntity,
        shiftId: Long?,
        customerId: Long?,
        customerNameSnapshot: String?
    ): CheckoutResult = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext CheckoutResult.Failure("Keranjang kosong")
        if (shiftId == null) return@withContext CheckoutResult.Failure("Buka shift terlebih dahulu sebelum melakukan transaksi")
        if (lines.any { it.quantity <= 0 || it.unitPrice < 0 }) {
            return@withContext CheckoutResult.Failure("Item transaksi memiliki quantity atau harga yang tidak valid")
        }

        val taxPct = settings.taxPercent()
        val svcPct = settings.servicePercent()
        val round = settings.rounding()
        val pricing = PricingEngine.price(lines, discount, taxPct, svcPct, round)
        val totals = pricing.totals

        val allocation = PaymentAllocation.settle(paidByMethod, totals.grandTotal)
        if (allocation.shortfall > 0 || allocation.settled.values.sum() != totals.grandTotal) {
            return@withContext CheckoutResult.Failure(
                if (paidByMethod.any { (method, amount) -> method != PaymentAllocation.CASH && amount > totals.grandTotal })
                    "Pembayaran non-tunai tidak boleh melebihi tagihan"
                else "Total dibayar kurang dari tagihan atau alokasi pembayaran tidak valid"
            )
        }

        try {
            var savedSale: SaleEntity? = null
            var savedItems: List<SaleItemEntity> = emptyList()
            var savedInvoice = ""

            db.withTransaction {
                // Active payment methods are revalidated atomically at checkout.
                for ((method, amount) in paidByMethod) {
                    if (amount <= 0L) continue
                    val configured = db.paymentMethodDao().byType(method)
                    if (configured == null || !configured.isActive) {
                        throw IllegalArgumentException("Metode pembayaran $method tidak aktif atau tidak dikenal")
                    }
                }
                // 1. validate stock inside the write lock
                for (line in lines) {
                    if (!line.trackInventory) continue
                    val stock = db.productDao().stockOf(line.productId) ?: continue
                    if (stock < line.quantity) {
                        throw StockException("${line.name}: stok tersisa $stock, diminta ${line.quantity}")
                    }
                }

                // 2. verify the shift remains open and reserve a unique invoice number.
                val currentShift = db.shiftDao().byId(shiftId)
                    ?: throw IllegalStateException("Shift tidak ditemukan")
                check(currentShift.status == "OPEN") { "Shift sudah ditutup" }
                check(currentShift.userId == user.id) { "Shift aktif bukan milik kasir yang login" }
                val cashier = db.userDao().byId(user.id) ?: throw IllegalStateException("Akun kasir tidak ditemukan")
                check(cashier.isActive) { "Akun kasir sudah tidak aktif" }

                var invoice: String? = null
                for (attempt in 0 until 5) {
                    val candidate = settings.nextInvoiceNumber()
                    if (saleDao.invoiceExists(candidate) == 0) {
                        invoice = candidate
                        break
                    }
                }
                val invoiceNumber = invoice ?: throw IllegalStateException("Gagal membuat nomor invoice unik")

                val now = System.currentTimeMillis()
                val sale = SaleEntity(
                    invoiceNumber = invoiceNumber,
                    customerId = customerId,
                    customerNameSnapshot = customerNameSnapshot,
                    userId = user.id,
                    userNameSnapshot = user.name,
                    shiftId = shiftId,
                    subtotal = totals.subtotal,
                    discount = totals.discount,
                    tax = totals.tax,
                    serviceCharge = totals.serviceCharge,
                    grandTotal = totals.grandTotal,
                    paidAmount = allocation.tendered,
                    changeAmount = allocation.change,
                    paymentStatus = "PAID",
                    transactionStatus = "COMPLETED",
                    createdAt = now
                )
                val items = lines.mapIndexed { index, line ->
                    SaleItemEntity(
                        saleId = 0,
                        productId = line.productId,
                        productNameSnapshot = line.name,
                        barcodeSnapshot = line.barcode ?: "",
                        quantity = line.quantity,
                        unitPrice = line.unitPrice,
                        discount = pricing.lineDiscounts[index],
                        subtotal = line.subtotal,
                        netTotal = pricing.lineNetTotals[index],
                        createdAt = now
                    )
                }
                check(items.sumOf { it.netTotal } == totals.grandTotal) {
                    "Snapshot nilai item tidak sama dengan total transaksi"
                }

                // Persist only the revenue settled against this sale; cash tender/change remains on SaleEntity.
                val pays = allocation.settled.map { (method, amount) ->
                    PaymentEntity(
                        saleId = 0,
                        method = method,
                        amount = amount,
                        referenceNumber = references[method] ?: "",
                        createdAt = now
                    )
                }
                val saleId = saleDao.saveFullSale(sale, items, pays)

                // 3. decrement stock + movement rows
                for (line in lines) {
                    if (!line.trackInventory) continue
                    db.productDao().applyDelta(line.productId, -line.quantity)
                    db.inventoryDao().insert(
                        InventoryMovementEntity(
                            productId = line.productId,
                            type = "SALE",
                            quantity = -line.quantity,
                            referenceId = saleId,
                            note = invoiceNumber,
                            userId = user.id,
                            createdAt = now
                        )
                    )
                }

                // 4. shift counters use settled revenue, never cash tender that includes change.
                val cashRevenue = allocation.settled["CASH"] ?: 0L
                val nonCashRevenue = totals.grandTotal - cashRevenue
                db.shiftDao().addSaleTotals(shiftId, cashRevenue, nonCashRevenue)

                // 5. audit log
                db.settingsDao().insertAudit(
                    AuditLogEntity(
                        userId = user.id,
                        action = "SALE_CREATE",
                        referenceType = "sale",
                        referenceId = saleId,
                        description = "$invoiceNumber total Rp ${totals.grandTotal}",
                        createdAt = now
                    )
                )

                savedSale = sale.copy(id = saleId)
                savedItems = items.map { it.copy(saleId = saleId) }
                savedInvoice = invoiceNumber
            }

            CheckoutResult.Success(savedSale!!, savedItems, savedInvoice)
        } catch (e: StockException) {
            CheckoutResult.Failure(e.message ?: "Stok tidak cukup")
        } catch (e: Exception) {
            CheckoutResult.Failure("Gagal menyimpan transaksi: ${e.message}")
        }
    }

    // ---- history ----
    data class HistoryFilters(
        val fromMs: Long? = null,
        val toMs: Long? = null,
        val cashierUserId: Long? = null,
        val method: String? = null,
        val status: String? = null,
        val queryInvoice: String? = null
    )

    private fun buildWhere(f: HistoryFilters, sb: StringBuilder, args: MutableList<Any>) {
        sb.append(" WHERE s.transactionStatus != 'VOID'")
        f.fromMs?.let { sb.append(" AND s.createdAt >= ?"); args.add(it) }
        f.toMs?.let { sb.append(" AND s.createdAt <= ?"); args.add(it) }
        f.cashierUserId?.let { sb.append(" AND s.userId = ?"); args.add(it) }
        f.status?.let { sb.append(" AND s.transactionStatus = ?"); args.add(it) }
        f.queryInvoice?.takeIf { it.isNotBlank() }?.let {
            sb.append(" AND s.invoiceNumber LIKE '%'||?||'%'")
            args.add(it.trim())
        }
    }

    suspend fun history(f: HistoryFilters, page: Int, pageSize: Int = 40): Pair<List<SaleEntity>, Int> =
        withContext(Dispatchers.IO) {
            val selSb = StringBuilder("SELECT DISTINCT s.* FROM sales s")
            val selArgs = mutableListOf<Any>()
            f.method?.let {
                selSb.append(" JOIN payments p ON p.saleId = s.id AND p.method = ?")
                selArgs.add(it)
            }
            buildWhere(f, selSb, selArgs)
            selSb.append(" ORDER BY s.createdAt DESC LIMIT ? OFFSET ?")
            selArgs.add(pageSize)
            selArgs.add(page * pageSize)

            val cntSb = StringBuilder("SELECT COUNT(DISTINCT s.id) FROM sales s")
            val cntArgs = mutableListOf<Any>()
            f.method?.let {
                cntSb.append(" JOIN payments p ON p.saleId = s.id AND p.method = ?")
                cntArgs.add(it)
            }
            buildWhere(f, cntSb, cntArgs)

            Pair(
                saleDao.historyRaw(SimpleSQLiteQuery(selSb.toString(), selArgs.toTypedArray())),
                saleDao.countRaw(SimpleSQLiteQuery(cntSb.toString(), cntArgs.toTypedArray()))
            )
        }

    suspend fun saleWithDetails(saleId: Long): Triple<SaleEntity, List<SaleItemEntity>, List<PaymentEntity>>? =
        withContext(Dispatchers.IO) {
            val sale = saleDao.saleById(saleId) ?: return@withContext null
            Triple(sale, saleDao.itemsFor(saleId), saleDao.paymentsFor(saleId))
        }

    suspend fun findByInvoice(inv: String) = withContext(Dispatchers.IO) { saleDao.saleByInvoice(inv) }

    // ---- reports ----
    suspend fun dailySeries(days: Int): List<SaleDao.DailyTotalRow> = withContext(Dispatchers.IO) {
        if (days <= 0) return@withContext emptyList()
        val todayStart = Dates.startOfDay()
        (days - 1 downTo 0).map { offset ->
            val start = Calendar.getInstance().apply {
                timeInMillis = todayStart
                add(Calendar.DAY_OF_YEAR, -offset)
            }.timeInMillis
            val nextStart = Calendar.getInstance().apply {
                timeInMillis = start
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val row = saleDao.totalsBetween(start, nextStart - 1)
            SaleDao.DailyTotalRow(dayStart = start, total = row.total, cnt = row.cnt)
        }
    }

    suspend fun todayStats(): SaleDao.TotalRow = withContext(Dispatchers.IO) {
        val start = Dates.startOfDay()
        val nextStart = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        saleDao.totalsBetween(start, nextStart - 1)
    }

    suspend fun rangeTotals(from: Long, to: Long) = withContext(Dispatchers.IO) { saleDao.totalsBetween(from, to) }
    /** Read-only gross/refund split of the same window; net is still gross - refund. */
    suspend fun rangeGrossAndRefund(from: Long, to: Long) =
        withContext(Dispatchers.IO) { saleDao.grossAndRefundBetween(from, to) }
    suspend fun methodBreakdown(from: Long, to: Long) = withContext(Dispatchers.IO) { saleDao.totalsByMethod(from, to) }
    suspend fun topProducts(from: Long, to: Long, limit: Int = 10) = withContext(Dispatchers.IO) { saleDao.topProducts(from, to, limit) }
    suspend fun cashierPerformance(from: Long, to: Long) = withContext(Dispatchers.IO) { saleDao.totalsByCashier(from, to) }
    suspend fun itemsSold(from: Long, to: Long) = withContext(Dispatchers.IO) { saleDao.itemsSoldBetween(from, to) }
}
