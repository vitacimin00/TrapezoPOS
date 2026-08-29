package com.trapezo.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.dao.SaleDao
import com.trapezo.pos.data.dao.TopProduct
import com.trapezo.pos.data.entity.UserEntity
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
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DashboardState(
    val netSales: Long = 0,
    val transactions: Int = 0,
    val itemsSold: Long = 0,
    val cash: Long = 0,
    val nonCash: Long = 0,
    val lowStock: Int = 0,
    val outOfStock: Int = 0,
    val shiftCashier: String? = null,
    val shiftOpenedAt: Long? = null,
    val topProducts: List<TopProduct> = emptyList(),
    val weekly: List<SaleDao.DailyTotalRow> = emptyList()
)

/**
 * Operational summary with deliberate hierarchy: today's net sales dominates, supporting
 * metrics sit below it. Every number comes from an existing repository query — nothing
 * is synthesised for decoration.
 */
@Composable
fun DashboardScreen(user: UserEntity) {
    var state by remember { mutableStateOf<DashboardState?>(null) }
    val widthClass = currentWidthClass

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val from = Dates.startOfDay()
            val to = Dates.endOfDay()
            val stats = AppGraph.sales.todayStats()
            val methods = AppGraph.sales.methodBreakdown(from, to)
            val open = AppGraph.db.shiftDao().anyOpenShift()
            state = DashboardState(
                netSales = stats.total,
                transactions = stats.cnt,
                itemsSold = AppGraph.sales.itemsSold(from, to).totalQty?.toLong() ?: 0L,
                cash = methods.firstOrNull { it.method == "CASH" }?.total ?: 0L,
                nonCash = methods.filter { it.method != "CASH" }.sumOf { it.total ?: 0L },
                lowStock = AppGraph.products.lowStock().size,
                outOfStock = AppGraph.products.outOfStock().size,
                shiftCashier = open?.userNameSnapshot,
                shiftOpenedAt = open?.openedAt,
                topProducts = AppGraph.sales.topProducts(from, to, 5),
                weekly = AppGraph.sales.dailySeries(7)
            )
        }
    }

    val data = state
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Dashboard",
            subtitle = "Ringkasan operasional ${Dates.dmy(System.currentTimeMillis())}",
            actions = {
                if (data?.shiftCashier != null) StatusBadge("Shift aktif", Tone.SUCCESS, Icons.Default.Schedule)
                else StatusBadge("Tidak ada shift", Tone.WARNING, Icons.Default.Schedule)
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (data == null) {
            LoadingState("Menghitung ringkasan hari ini…")
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            // Primary metric — visually dominant.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = Radius.panel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        "PENJUALAN BERSIH HARI INI",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        Money.fmt(data.netSales),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${data.transactions} transaksi • ${data.itemsSold} item terjual",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Supporting metrics.
            if (widthClass.isExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    MetricCard("Tunai", Money.fmt(data.cash), Modifier.weight(1f))
                    MetricCard("Non-tunai", Money.fmt(data.nonCash), Modifier.weight(1f))
                    MetricCard(
                        "Stok menipis",
                        data.lowStock.toString(),
                        Modifier.weight(1f),
                        tone = if (data.lowStock > 0) Tone.WARNING else Tone.NEUTRAL
                    )
                    MetricCard(
                        "Stok habis",
                        data.outOfStock.toString(),
                        Modifier.weight(1f),
                        tone = if (data.outOfStock > 0) Tone.DANGER else Tone.NEUTRAL
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    MetricCard("Tunai", Money.fmt(data.cash), Modifier.weight(1f))
                    MetricCard("Non-tunai", Money.fmt(data.nonCash), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    MetricCard(
                        "Stok menipis",
                        data.lowStock.toString(),
                        Modifier.weight(1f),
                        tone = if (data.lowStock > 0) Tone.WARNING else Tone.NEUTRAL
                    )
                    MetricCard(
                        "Stok habis",
                        data.outOfStock.toString(),
                        Modifier.weight(1f),
                        tone = if (data.outOfStock > 0) Tone.DANGER else Tone.NEUTRAL
                    )
                }
            }

            // Shift status.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = Radius.card,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text("Status shift", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (data.shiftCashier != null) {
                                "Kasir ${data.shiftCashier} • dibuka ${data.shiftOpenedAt?.let(Dates::hhmm).orEmpty()}"
                            } else {
                                "Belum ada shift dibuka hari ini"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 7-day trend (simple business bar chart, no extra dependency).
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Tren 7 hari")
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = Radius.card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Space.lg)) {
                        if (data.weekly.all { (it.total ?: 0L) == 0L }) {
                            Text(
                                "Belum ada penjualan pada 7 hari terakhir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            WeeklyBars(data.weekly)
                        }
                    }
                }
            }

            // Top products today.
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Produk terlaris hari ini")
                if (data.topProducts.isEmpty()) {
                    Text(
                        "Belum ada produk terjual hari ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    data.topProducts.forEach { product ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(Space.sm))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    product.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${product.qtySold} item",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MoneyText(product.revenue, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Compact bar chart for the 7-day trend; day labels stay readable on phones. */
@Composable
private fun WeeklyBars(rows: List<SaleDao.DailyTotalRow>) {
    val values = rows.map { it.total ?: 0L }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barWidth = (slot * 0.5f).coerceAtLeast(6f)
            values.forEachIndexed { index, value ->
                val h = (value.toFloat() / maxValue * (size.height - 8f)).coerceAtLeast(3f)
                val x = slot * index + (slot - barWidth) / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
        Spacer(Modifier.size(Space.xs))
        Row(Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(Dates.weekdayShort(row.dayStart), style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${row.cnt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "Batang: penjualan bersih • angka: jumlah transaksi",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
}
