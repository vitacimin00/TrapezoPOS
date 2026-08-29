package com.trapezo.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.dao.CashierTotal
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.dao.TopProduct
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.ui.components.AmountRow
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MetricCard
import com.trapezo.pos.ui.components.MoneyText
import com.trapezo.pos.ui.components.ScreenHeader
import com.trapezo.pos.ui.components.SectionHeader
import com.trapezo.pos.ui.components.StatusBadge
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.currentWidthClass
import com.trapezo.pos.ui.components.isExpanded
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import com.trapezo.pos.ui.theme.TrapezoStatus
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ReportState(
    val gross: Long = 0,
    val refunded: Long = 0,
    val net: Long = 0,
    val transactions: Int = 0,
    val items: Long = 0,
    val methods: List<SaleDao.MethodTotalRow> = emptyList(),
    val topProducts: List<TopProduct> = emptyList(),
    val cashiers: List<CashierTotal> = emptyList(),
    val daily: List<SaleDao.DailyTotalRow> = emptyList(),
    val shifts: List<ShiftEntity> = emptyList(),
    val cashMovements: List<CashMovementEntity> = emptyList()
)

private suspend fun localDailyTotals(from: Long, to: Long): List<SaleDao.DailyTotalRow> {
    if (to < from) return emptyList()
    val rows = mutableListOf<SaleDao.DailyTotalRow>()
    var dayStart = Dates.startOfDay(from)
    while (dayStart <= to) {
        val nextStart = Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val rangeStart = maxOf(dayStart, from)
        val rangeEnd = minOf(nextStart - 1, to)
        val total = AppGraph.sales.rangeTotals(rangeStart, rangeEnd)
        rows += SaleDao.DailyTotalRow(dayStart = dayStart, total = total.total, cnt = total.cnt)
        dayStart = nextStart
    }
    return rows
}

/**
 * Reports presentation only. All period calculations still come from the locked repository
 * queries; this screen re-organises the hierarchy as Gross → Refund → Net.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    var period by remember { mutableStateOf("TODAY") }
    var fromMs by remember { mutableStateOf<Long?>(null) }
    var toMs by remember { mutableStateOf<Long?>(null) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ReportState?>(null) }
    val widthClass = currentWidthClass
    val customPeriodInvalid = period == "CUSTOM" && fromMs != null && toMs != null && fromMs!! > toMs!!

    LaunchedEffect(period, fromMs, toMs) {
        if (customPeriodInvalid) {
            state = ReportState()
            return@LaunchedEffect
        }
        state = null
        withContext(Dispatchers.IO) {
            // A single period window feeds every section, per the locked Track D contract.
            val (from, to) = when (period) {
                "WEEK" -> Dates.daysAgoStart(6) to Dates.endOfDay()
                "MONTH" -> Dates.daysAgoStart(29) to Dates.endOfDay()
                "CUSTOM" -> (fromMs ?: Dates.daysAgoStart(6)) to (toMs ?: Dates.endOfDay())
                else -> Dates.startOfDay() to Dates.endOfDay()
            }
            val total = AppGraph.sales.rangeTotals(from, to)
            val split = AppGraph.sales.rangeGrossAndRefund(from, to)
            state = ReportState(
                gross = split.gross,
                refunded = split.refunded,
                net = total.total,
                transactions = total.cnt,
                items = AppGraph.sales.itemsSold(from, to).totalQty?.toLong() ?: 0L,
                methods = AppGraph.sales.methodBreakdown(from, to),
                topProducts = AppGraph.sales.topProducts(from, to),
                cashiers = AppGraph.sales.cashierPerformance(from, to),
                daily = localDailyTotals(from, to),
                shifts = AppGraph.db.shiftDao().shiftsOverlapping(from, to, 50, 0),
                cashMovements = AppGraph.db.shiftDao().cashMovementsBetween(from, to, 50, 0)
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Laporan",
            subtitle = when (period) {
                "WEEK" -> "7 hari terakhir"
                "MONTH" -> "30 hari terakhir"
                "CUSTOM" -> "Periode custom"
                else -> "Hari ini"
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf(
                    "TODAY" to "Hari ini",
                    "WEEK" to "7 hari",
                    "MONTH" to "30 hari",
                    "CUSTOM" to "Custom"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = period == id,
                        onClick = { period = id },
                        label = { Text(label) },
                        shape = Radius.control,
                        modifier = Modifier.heightIn(min = Touch.min)
                    )
                }
            }
            if (period == "CUSTOM") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = fromMs != null,
                        onClick = { pickFrom = true },
                        label = { Text(if (fromMs != null) "Dari ${Dates.dmy(fromMs!!)}" else "Pilih tanggal awal") },
                        shape = Radius.control
                    )
                    FilterChip(
                        selected = toMs != null,
                        onClick = { pickTo = true },
                        label = { Text(if (toMs != null) "Sampai ${Dates.dmy(toMs!!)}" else "Pilih tanggal akhir") },
                        shape = Radius.control
                    )
                }
                if (customPeriodInvalid) {
                    Text(
                        "Tanggal awal tidak boleh setelah tanggal akhir.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        val data = state
        if (data == null) {
            LoadingState("Menghitung laporan…")
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Space.lg, end = Space.lg, bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            // Gross → Refund → Net, with Net visually strongest.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = Radius.panel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(
                                "PENJUALAN BERSIH",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                Money.fmt(data.net),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${data.transactions} transaksi • ${data.items} item",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (widthClass.isExpanded) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            MetricCard("Penjualan bruto", Money.fmt(data.gross), Modifier.weight(1f))
                            MetricCard(
                                "Refund",
                                Money.fmt(data.refunded),
                                Modifier.weight(1f),
                                tone = if (data.refunded > 0) Tone.DANGER else Tone.NEUTRAL
                            )
                            MetricCard(
                                "Rata-rata transaksi",
                                Money.fmt(if (data.transactions > 0) data.net / data.transactions else 0L),
                                Modifier.weight(1f)
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            MetricCard("Bruto", Money.fmt(data.gross), Modifier.weight(1f))
                            MetricCard(
                                "Refund",
                                Money.fmt(data.refunded),
                                Modifier.weight(1f),
                                tone = if (data.refunded > 0) Tone.DANGER else Tone.NEUTRAL
                            )
                        }
                        MetricCard(
                            "Rata-rata transaksi",
                            Money.fmt(if (data.transactions > 0) data.net / data.transactions else 0L),
                            Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item { SectionHeader("Tren penjualan bersih") }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = Radius.card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (data.daily.isEmpty()) {
                            Text(
                                "Belum ada transaksi pada periode ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val maxTotal = data.daily.maxOf { it.total ?: 0L }.coerceAtLeast(1L)
                            data.daily.forEach { day ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            Dates.dmy(day.dayStart),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.width(88.dp)
                                        )
                                        Text(
                                            "${day.cnt} trx",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(56.dp)
                                        )
                                        MoneyText(day.total ?: 0L, Modifier.weight(1f), weight = FontWeight.Medium)
                                    }
                                    LinearProgressIndicator(
                                        progress = { ((day.total ?: 0L).toFloat() / maxTotal).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Metode pembayaran") }
            if (data.methods.isEmpty()) {
                item { EmptyReportLine("Belum ada pembayaran pada periode ini.") }
            } else {
                items(data.methods, key = { it.method }) { row ->
                    ReportLine(Labels.paymentMethod(row.method), "${row.cnt} transaksi", row.total ?: 0)
                }
            }

            item { SectionHeader("Produk terlaris") }
            if (data.topProducts.isEmpty()) {
                item { EmptyReportLine("Belum ada produk terjual pada periode ini.") }
            } else {
                items(data.topProducts, key = { "${it.productId}-${it.name}" }) { row ->
                    ReportLine(row.name, "${row.qtySold} item", row.revenue)
                }
            }

            item { SectionHeader("Performa kasir") }
            if (data.cashiers.isEmpty()) {
                item { EmptyReportLine("Belum ada penjualan kasir pada periode ini.") }
            } else {
                items(data.cashiers, key = { it.userName }) { row ->
                    ReportLine(row.userName, "${row.txCount} transaksi", row.total)
                }
            }

            item { SectionHeader("Shift") }
            if (data.shifts.isEmpty()) {
                item { EmptyReportLine("Belum ada shift pada periode ini.") }
            } else {
                items(data.shifts, key = { it.id }) { shift ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = Radius.card,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    shift.userNameSnapshot.ifBlank { "Kasir #${shift.userId}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                StatusBadge(
                                    Labels.shiftStatus(shift.status),
                                    if (shift.status == "OPEN") Tone.SUCCESS else Tone.NEUTRAL
                                )
                            }
                            Text(
                                "${Dates.dmyhm(shift.openedAt)} • kas awal ${Money.fmt(shift.openingCash)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AmountRow("Tunai", shift.totalCashSales)
                            AmountRow("Non-tunai", shift.totalNonCashSales)
                            AmountRow("Kas seharusnya", shift.expectedCash)
                            if (shift.status == "CLOSED") {
                                AmountRow(
                                    "Selisih",
                                    shift.difference,
                                    tone = if (shift.difference == 0L) Tone.SUCCESS else Tone.DANGER
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Kas masuk / kas keluar") }
            if (data.cashMovements.isEmpty()) {
                item { EmptyReportLine("Belum ada kas masuk atau keluar pada periode ini.") }
            } else {
                items(data.cashMovements, key = { it.id }) { movement ->
                    val isIn = movement.type == "CASH_IN"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Labels.cashMovement(movement.type), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Dates.dmyhm(movement.createdAt)}${if (movement.note.isBlank()) "" else " • ${movement.note}"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        MoneyText(
                            if (isIn) movement.amount else -movement.amount,
                            color = if (isIn) TrapezoStatus.success else TrapezoStatus.warning,
                            weight = FontWeight.SemiBold
                        )
                    }
                }
            }

            item { SectionHeader("Stok saat ini (snapshot, bukan periode)") }
            item { StockSnapshot() }
        }
    }

    if (pickFrom) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = fromMs)
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    fromMs = pickerState.selectedDateMillis?.let { Dates.startOfDay(it) }
                    pickFrom = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("Batal") } }
        ) { DatePicker(state = pickerState) }
    }
    if (pickTo) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = toMs)
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = {
                TextButton(onClick = {
                    toMs = pickerState.selectedDateMillis?.let { Dates.endOfDay(it) }
                    pickTo = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("Batal") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun EmptyReportLine(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Space.xs)
    )
}

@Composable
private fun ReportLine(title: String, subtitle: String, amount: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MoneyText(amount, weight = FontWeight.Medium)
    }
}

@Composable
private fun StockSnapshot() {
    var low by remember { mutableStateOf(0) }
    var out by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            low = AppGraph.products.lowStock().size
            out = AppGraph.products.outOfStock().size
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        MetricCard("Stok menipis", low.toString(), Modifier.weight(1f), tone = if (low > 0) Tone.WARNING else Tone.NEUTRAL)
        MetricCard("Stok habis", out.toString(), Modifier.weight(1f), tone = if (out > 0) Tone.DANGER else Tone.NEUTRAL)
    }
}
