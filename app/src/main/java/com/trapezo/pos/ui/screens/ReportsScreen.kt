package com.trapezo.pos.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.dao.CashierTotal
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.dao.TopProduct
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ReportState(
    val total: SaleDao.TotalRow = SaleDao.TotalRow(0, 0),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    var period by remember { mutableStateOf("TODAY") }
    var fromMs by remember { mutableStateOf<Long?>(null) }
    var toMs by remember { mutableStateOf<Long?>(null) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(ReportState()) }
    LaunchedEffect(period, fromMs, toMs) {
        withContext(Dispatchers.IO) {
            val (from, to) = when (period) {
                "WEEK" -> Dates.daysAgoStart(6) to Dates.endOfDay()
                "MONTH" -> Dates.daysAgoStart(29) to Dates.endOfDay()
                "CUSTOM" -> (fromMs ?: Dates.daysAgoStart(6)) to (toMs ?: Dates.endOfDay())
                else -> Dates.startOfDay() to Dates.endOfDay()
            }
            val total = AppGraph.sales.rangeTotals(from, to)
            val items = AppGraph.sales.itemsSold(from, to).totalQty?.toLong() ?: 0L
            state = ReportState(
                total = total,
                items = items,
                methods = AppGraph.sales.methodBreakdown(from, to),
                topProducts = AppGraph.sales.topProducts(from, to),
                cashiers = AppGraph.sales.cashierPerformance(from, to),
                daily = localDailyTotals(from, to),
                shifts = AppGraph.db.shiftDao().allShifts(50, 0),
                cashMovements = AppGraph.db.shiftDao().allCashMovements(50, 0)
            )
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Laporan", fontWeight = FontWeight.Bold) }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TODAY" to "Hari ini", "WEEK" to "7 hari", "MONTH" to "30 hari", "CUSTOM" to "Custom").forEach { (id, label) ->
                        FilterChip(selected = period == id, onClick = { period = id }, label = { Text(label) })
                    }
                }
            }
            if (period == "CUSTOM") {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = fromMs != null, onClick = { pickFrom = true }, label = { Text(if (fromMs != null) "Dari ${Dates.dmy(fromMs!!)}" else "Dari…") })
                        FilterChip(selected = toMs != null, onClick = { pickTo = true }, label = { Text(if (toMs != null) "Sampai ${Dates.dmy(toMs!!)}" else "Sampai…") })
                    }
                }
            }
            item { Text("Laporan penjualan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportMetric("Penjualan", Money.fmt(state.total.total), Modifier.weight(1f))
                    ReportMetric("Transaksi", state.total.cnt.toString(), Modifier.weight(1f))
                    ReportMetric("Item", state.items.toString(), Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Tren penjualan", fontWeight = FontWeight.SemiBold)
                        if (state.daily.isEmpty()) {
                            Text("Belum ada transaksi pada periode ini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            state.daily.forEach { day ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(Dates.dmy(day.dayStart), Modifier.weight(1f))
                                    Text("${day.cnt} transaksi", Modifier.width(100.dp))
                                    Text(Money.fmt(day.total ?: 0))
                                }
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Laporan pembayaran") }
            if (state.methods.isEmpty()) item { EmptyReport("Belum ada pembayaran pada periode ini.") }
            else items(state.methods, key = { it.method }) { row -> ReportLine(methodLabel(row.method), row.cnt.toString() + " transaksi", row.total ?: 0) }
            item { SectionTitle("Produk terlaris") }
            if (state.topProducts.isEmpty()) item { EmptyReport("Belum ada produk terjual pada periode ini.") }
            else items(state.topProducts, key = { "${it.productId}-${it.name}" }) { row -> ReportLine(row.name, "${row.qtySold} item", row.revenue) }
            item { SectionTitle("Laporan kasir") }
            if (state.cashiers.isEmpty()) item { EmptyReport("Belum ada penjualan kasir pada periode ini.") }
            else items(state.cashiers, key = { it.userName }) { row -> ReportLine(row.userName, "${row.txCount} transaksi", row.total) }
            item { SectionTitle("Shift kasir") }
            if (state.shifts.isEmpty()) item { EmptyReport("Belum ada shift tercatat.") }
            else items(state.shifts, key = { it.id }) { shift ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(shift.userNameSnapshot.ifBlank { "Kasir #${shift.userId}" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(shift.status, color = if (shift.status == "OPEN") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                        Text("${Dates.dmyhm(shift.openedAt)} • Modal ${Money.fmt(shift.openingCash)}", style = MaterialTheme.typography.bodySmall)
                        Text("Tunai ${Money.fmt(shift.totalCashSales)} • Non-tunai ${Money.fmt(shift.totalNonCashSales)} • Kas seharusnya ${Money.fmt(shift.expectedCash)}", style = MaterialTheme.typography.bodySmall)
                        if (shift.status == "CLOSED") {
                            Text("Kas aktual ${Money.fmt(shift.actualCash)} • Selisih ${Money.fmt(shift.difference)}", style = MaterialTheme.typography.bodySmall, color = if (shift.difference == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item { SectionTitle("Cash in / Cash out") }
            if (state.cashMovements.isEmpty()) item { EmptyReport("Belum ada cash in/out tercatat.") }
            else items(state.cashMovements, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (m.type == "CASH_IN") "Cash In" else "Cash Out", fontWeight = FontWeight.SemiBold, color = if (m.type == "CASH_IN") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text("${Dates.dmyhm(m.createdAt)}${if (m.note.isBlank()) "" else " • ${m.note}"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (m.type == "CASH_IN") "+${Money.fmt(m.amount)}" else "-${Money.fmt(m.amount)}", fontWeight = FontWeight.Bold, color = if (m.type == "CASH_IN") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            item { SectionTitle("Stok") }
            item { StockReport() }
        }
    }
    if (pickFrom) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = fromMs)
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = { TextButton(onClick = { fromMs = pickerState.selectedDateMillis?.let { Dates.startOfDay(it) }; pickFrom = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("BATAL") } }
        ) { DatePicker(state = pickerState) }
    }
    if (pickTo) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = toMs)
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = { TextButton(onClick = { toMs = pickerState.selectedDateMillis?.let { Dates.endOfDay(it) }; pickTo = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("BATAL") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun ReportMetric(label: String, value: String, modifier: Modifier) = Card(modifier) {
    Column(Modifier.padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun EmptyReport(value: String) = Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun ReportLine(title: String, subtitle: String, amount: Long) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text(Money.fmt(amount), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StockReport() {
    var low by remember { mutableStateOf(0) }
    var out by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            low = AppGraph.products.lowStock().size
            out = AppGraph.products.outOfStock().size
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Stok rendah: $low")
            Text("Stok habis: $out")
        }
    }
}

private fun methodLabel(method: String) = when (method) {
    "CASH" -> "Tunai"
    "QRIS" -> "QRIS"
    "TRANSFER" -> "Transfer"
    "DEBIT" -> "Debit"
    "CREDIT_CARD" -> "Kartu Kredit"
    "EWALLET" -> "E-Wallet"
    else -> method
}
