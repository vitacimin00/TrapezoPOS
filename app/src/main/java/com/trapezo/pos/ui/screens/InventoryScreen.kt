package com.trapezo.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.dao.MovementWithProduct
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.InlineLoading
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MetricCard
import com.trapezo.pos.ui.components.ScreenHeader
import com.trapezo.pos.ui.components.SearchField
import com.trapezo.pos.ui.components.SectionHeader
import com.trapezo.pos.ui.components.StatusBadge
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.currentWidthClass
import com.trapezo.pos.ui.components.isExpanded
import com.trapezo.pos.ui.components.rememberFeedback
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import com.trapezo.pos.ui.theme.TrapezoStatus
import com.trapezo.pos.utils.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inventory is the operational stock ledger: health summary, searchable stock list,
 * and the recent movement history with human-readable movement types.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(userId: Long, canAdjust: Boolean) {
    val pageSize = 50
    val movementPageSize = 20
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    val widthClass = currentWidthClass

    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var products by remember { mutableStateOf(emptyList<ProductEntity>()) }
    var productPage by remember { mutableIntStateOf(0) }
    var hasMoreProducts by remember { mutableStateOf(false) }
    var movements by remember { mutableStateOf(emptyList<MovementWithProduct>()) }
    var movementPage by remember { mutableIntStateOf(0) }
    var hasMoreMovements by remember { mutableStateOf(false) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var adjustment by remember { mutableStateOf<ProductEntity?>(null) }
    var lowCount by remember { mutableIntStateOf(0) }
    var outCount by remember { mutableIntStateOf(0) }
    var showMovements by remember { mutableStateOf(false) }

    fun refresh() {
        val version = ++requestVersion
        scope.launch {
            loading = true
            val (loadedProducts, total) = AppGraph.products.filteredPage(
                query, null, "ACTIVE", true, filter, "stock_asc", 0, pageSize
            )
            val loadedMovements = AppGraph.db.inventoryDao().recentWithProductName(movementPageSize, 0)
            val low = AppGraph.products.lowStock().size
            val out = AppGraph.products.outOfStock().size
            if (version != requestVersion) return@launch
            products = loadedProducts.distinctBy { it.id }
            productPage = 0
            hasMoreProducts = products.size < total
            movements = loadedMovements.distinctBy { it.id }
            movementPage = 0
            hasMoreMovements = loadedMovements.size == movementPageSize
            lowCount = low
            outCount = out
            loading = false
        }
    }
    fun loadMoreProducts() {
        val version = requestVersion
        val nextPage = productPage + 1
        scope.launch {
            loadingMore = true
            val (loaded, total) = AppGraph.products.filteredPage(
                query, null, "ACTIVE", true, filter, "stock_asc", nextPage, pageSize
            )
            if (version != requestVersion) return@launch
            products = (products + loaded).distinctBy { it.id }
            productPage = nextPage
            hasMoreProducts = products.size < total
            loadingMore = false
        }
    }
    fun loadMoreMovements() {
        val version = requestVersion
        val nextPage = movementPage + 1
        scope.launch {
            val loaded = AppGraph.db.inventoryDao().recentWithProductName(movementPageSize, nextPage * movementPageSize)
            if (version != requestVersion) return@launch
            movements = (movements + loaded).distinctBy { it.id }
            movementPage = nextPage
            hasMoreMovements = loaded.size == movementPageSize
        }
    }

    LaunchedEffect(filter, query) {
        val version = ++requestVersion
        loading = true
        delay(300)
        val (loadedProducts, total) = AppGraph.products.filteredPage(
            query, null, "ACTIVE", true, filter, "stock_asc", 0, pageSize
        )
        val loadedMovements = AppGraph.db.inventoryDao().recentWithProductName(movementPageSize, 0)
        val low = AppGraph.products.lowStock().size
        val out = AppGraph.products.outOfStock().size
        if (version != requestVersion) return@LaunchedEffect
        products = loadedProducts.distinctBy { it.id }
        productPage = 0
        hasMoreProducts = products.size < total
        movements = loadedMovements.distinctBy { it.id }
        movementPage = 0
        hasMoreMovements = loadedMovements.size == movementPageSize
        lowCount = low
        outCount = out
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Stok",
            subtitle = "Pantau ketersediaan dan riwayat pergerakan stok",
            actions = {
                if (!widthClass.isExpanded) {
                    OutlinedButton(
                        onClick = { showMovements = !showMovements },
                        shape = Radius.field,
                        modifier = Modifier.heightIn(min = Touch.control)
                    ) {
                        Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text(if (showMovements) "Daftar stok" else "Riwayat")
                    }
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                MetricCard(
                    "Stok menipis",
                    lowCount.toString(),
                    Modifier.weight(1f),
                    tone = if (lowCount > 0) Tone.WARNING else Tone.NEUTRAL
                )
                MetricCard(
                    "Stok habis",
                    outCount.toString(),
                    Modifier.weight(1f),
                    tone = if (outCount > 0) Tone.DANGER else Tone.NEUTRAL
                )
            }
            SearchField(query, { query = it }, "Cari produk, SKU, atau barcode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf("ALL", "LOW", "OUT").forEach { code ->
                    FilterChip(
                        selected = filter == code,
                        onClick = { filter = code },
                        label = { Text(Labels.stockFilter(code)) },
                        shape = Radius.control,
                        modifier = Modifier.heightIn(min = Touch.min)
                    )
                }
            }
        }

        if (widthClass.isExpanded) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.58f)) {
                    StockList(
                        products = products,
                        loading = loading,
                        loadingMore = loadingMore,
                        hasMore = hasMoreProducts,
                        query = query,
                        filter = filter,
                        canAdjust = canAdjust,
                        onLoadMore = ::loadMoreProducts,
                        onAdjust = { adjustment = it }
                    )
                }
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(0.42f)) {
                    MovementList(movements, hasMoreMovements, ::loadMoreMovements)
                }
            }
        } else {
            if (showMovements) {
                MovementList(movements, hasMoreMovements, ::loadMoreMovements)
            } else {
                StockList(
                    products = products,
                    loading = loading,
                    loadingMore = loadingMore,
                    hasMore = hasMoreProducts,
                    query = query,
                    filter = filter,
                    canAdjust = canAdjust,
                    onLoadMore = ::loadMoreProducts,
                    onAdjust = { adjustment = it }
                )
            }
        }
    }

    adjustment?.let { product ->
        StockAdjustSheet(
            product = product,
            userId = userId,
            onDismiss = { adjustment = null },
            onSaved = { message -> adjustment = null; feedback?.success(message); refresh() }
        )
    }
}

@Composable
private fun StockList(
    products: List<ProductEntity>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    query: String,
    filter: String,
    canAdjust: Boolean,
    onLoadMore: () -> Unit,
    onAdjust: (ProductEntity) -> Unit
) {
    when {
        loading -> LoadingState("Memuat data stok…")
        products.isEmpty() && query.isNotBlank() -> EmptyState(
            title = "Tidak ada hasil",
            message = "Tidak ada produk dilacak yang cocok untuk \"$query\".",
            icon = Icons.Default.Inventory2
        )
        products.isEmpty() && filter != "ALL" -> EmptyState(
            title = "Tidak ada produk pada status ini",
            message = "Tidak ada produk berstatus ${Labels.stockFilter(filter).lowercase()} saat ini.",
            icon = Icons.Default.Inventory2
        )
        products.isEmpty() -> EmptyState(
            title = "Belum ada produk dilacak",
            message = "Aktifkan pelacakan stok pada produk agar muncul di ledger ini.",
            icon = Icons.Default.Inventory2
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Space.lg, end = Space.lg, bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            items(products, key = { it.id }) { product ->
                val outOfStock = product.stockQty <= 0
                val lowStock = !outOfStock && product.stockQty <= product.lowStockAlert
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = Radius.card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                product.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${product.sku.ifBlank { product.barcode.ifBlank { "Tanpa SKU" } }} • batas ${product.lowStockAlert} ${product.uom}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            when {
                                outOfStock -> StatusBadge("Stok habis", Tone.DANGER)
                                lowStock -> StatusBadge("Stok menipis", Tone.WARNING)
                                else -> StatusBadge("Stok aman", Tone.SUCCESS)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${product.stockQty}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                product.uom,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (canAdjust) {
                            OutlinedButton(
                                onClick = { onAdjust(product) },
                                shape = Radius.control,
                                modifier = Modifier.heightIn(min = Touch.control)
                            ) { Text("Adjust") }
                        }
                    }
                }
            }
            if (hasMore) {
                item {
                    if (loadingMore) InlineLoading()
                    else TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Muat lebih banyak")
                    }
                }
            }
        }
    }
}

/** Movement ledger with direction icons plus human-readable type labels. */
@Composable
private fun MovementList(
    movements: List<MovementWithProduct>,
    hasMore: Boolean,
    onLoadMore: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(
            "Pergerakan stok terbaru",
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
        )
        if (movements.isEmpty()) {
            EmptyState(
                title = "Belum ada pergerakan stok",
                message = "Penjualan, refund, import, dan adjustment akan tercatat di sini.",
                icon = Icons.Default.SwapVert
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.lg, end = Space.lg, bottom = Space.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(movements, key = { it.id }) { movement ->
                    val positive = movement.quantity >= 0
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (positive) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = if (positive) "Stok masuk" else "Stok keluar",
                            tint = if (positive) TrapezoStatus.success else TrapezoStatus.warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                movement.productName ?: "Produk #${movement.productId}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${Labels.movementType(movement.type)} • ${Dates.dmyhm(movement.createdAt)}" +
                                    if (movement.note.isBlank()) "" else " • ${movement.note}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            if (positive) "+${movement.quantity}" else movement.quantity.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (positive) TrapezoStatus.success else TrapezoStatus.warning
                        )
                    }
                }
                if (hasMore) {
                    item {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text("Muat riwayat lebih banyak")
                        }
                    }
                }
            }
        }
    }
}
