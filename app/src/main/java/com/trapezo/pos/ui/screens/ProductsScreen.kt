package com.trapezo.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.domain.model.OperationalInputRules
import com.trapezo.pos.excel.ProductExcelService
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.FormSection
import com.trapezo.pos.ui.components.InlineLoading
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MoneyText
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
import com.trapezo.pos.utils.Money
import com.trapezo.pos.utils.PhotoStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(userId: Long, canManage: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    val widthClass = currentWidthClass
    val categoryList by AppGraph.products.categoriesFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("name_asc") }
    var stockFilter by remember { mutableStateOf("ALL") }
    var lifecycleFilter by remember { mutableStateOf("ACTIVE") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var products by remember { mutableStateOf(emptyList<ProductEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<ProductEntity?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var adjustProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ProductExcelService.Preview?>(null) }
    var lifecycleConfirm by remember { mutableStateOf<ProductEntity?>(null) }
    val excel = remember { ProductExcelService(AppGraph.db) }

    fun reloadPageZero() {
        page = 0
        reloadKey++
    }

    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }
    LaunchedEffect(debouncedQuery, sort, stockFilter, lifecycleFilter, categoryId) { page = 0 }
    LaunchedEffect(debouncedQuery, sort, stockFilter, lifecycleFilter, categoryId, page, reloadKey) {
        val version = ++requestVersion
        val requestedPage = page
        if (requestedPage == 0) loading = true else loadingMore = true
        val result = AppGraph.products.filteredPage(
            debouncedQuery, categoryId, lifecycleFilter, false, stockFilter, sort, requestedPage
        )
        if (version != requestVersion) return@LaunchedEffect
        products = if (requestedPage == 0) result.first.distinctBy { it.id }
        else (products + result.first).distinctBy { it.id }
        total = result.second
        loading = false
        loadingMore = false
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openInputStream(uri)?.use { importPreview = excel.preview(it) }
                    ?: feedback?.error("File Excel tidak dapat dibuka")
            } catch (e: Exception) {
                feedback?.error(e.message ?: "Gagal membaca file Excel")
            }
        }
    }
    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { excel.writeTemplate(it) }
            feedback?.success("Template Excel kosong tersimpan")
        } catch (e: Exception) {
            feedback?.error("Template gagal dibuat: ${e.message}")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { excel.export(it) }
                feedback?.success("Export produk tersimpan")
            } catch (e: Exception) {
                feedback?.error("Export gagal: ${e.message}")
            }
        }
    }

    val activeFilterCount = listOf(
        stockFilter != "ALL",
        lifecycleFilter != "ACTIVE",
        categoryId != null,
        sort != "name_asc"
    ).count { it }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Produk",
            subtitle = "$total produk sesuai filter",
            actions = {
                if (canManage) {
                    Row {
                        IconButton(onClick = { templateLauncher.launch("TrapezoPOS_Template_Produk.xlsx") }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Unduh template Excel")
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf(XLSX_MIME, "application/vnd.ms-excel")) }) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Import dari Excel")
                        }
                        IconButton(onClick = { exportLauncher.launch("TrapezoPOS_Produk.xlsx") }) {
                            Icon(Icons.Default.Inventory2, contentDescription = "Export ke Excel")
                        }
                    }
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            SearchField(query, { query = it }, "Cari nama, SKU, atau barcode")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                AssistChip(
                    onClick = { filtersOpen = true },
                    label = { Text(if (activeFilterCount > 0) "Filter • $activeFilterCount" else "Filter") },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                if (activeFilterCount > 0) {
                    TextButton(onClick = {
                        stockFilter = "ALL"; lifecycleFilter = "ACTIVE"; categoryId = null; sort = "name_asc"
                    }) { Text("Reset") }
                }
                Spacer(Modifier.weight(1f))
                if (canManage) {
                    Button(
                        onClick = { editorTarget = null; editorOpen = true },
                        shape = Radius.field,
                        modifier = Modifier.heightIn(min = Touch.control)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text("Tambah Produk")
                    }
                }
            }
            if (activeFilterCount > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (stockFilter != "ALL") StatusBadge(Labels.stockFilter(stockFilter), Tone.WARNING)
                    if (lifecycleFilter != "ACTIVE") StatusBadge("Status: ${Labels.lifecycleFilter(lifecycleFilter)}", Tone.INFO)
                    categoryId?.let { id ->
                        categoryList.firstOrNull { it.id == id }?.let { StatusBadge("Kategori: ${it.name}", Tone.INFO) }
                    }
                }
            }
        }

        when {
            loading -> LoadingState("Memuat daftar produk…")
            products.isEmpty() && debouncedQuery.isNotBlank() -> EmptyState(
                title = "Tidak ada hasil",
                message = "Tidak ada produk cocok untuk \"$debouncedQuery\". Coba kata kunci lain atau ubah filter.",
                icon = Icons.Default.Inventory2
            )
            products.isEmpty() && activeFilterCount > 0 -> EmptyState(
                title = "Tidak ada produk pada filter ini",
                message = "Ubah atau reset filter untuk melihat produk lain.",
                icon = Icons.Default.Tune,
                actionLabel = "Reset filter",
                onAction = { stockFilter = "ALL"; lifecycleFilter = "ACTIVE"; categoryId = null; sort = "name_asc" }
            )
            products.isEmpty() -> EmptyState(
                title = "Belum ada produk",
                message = "Tambahkan produk pertama Anda atau import katalog dari Excel.",
                icon = Icons.Default.Inventory2,
                actionLabel = if (canManage) "Tambah Produk" else null,
                onAction = if (canManage) ({ editorTarget = null; editorOpen = true }) else null
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.lg, end = Space.lg, bottom = Space.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(products, key = { it.id }) { product ->
                    ProductRow(
                        product = product,
                        canManage = canManage,
                        dense = widthClass.isExpanded,
                        onEdit = { editorTarget = product; editorOpen = true },
                        onAdjust = { adjustProduct = product },
                        onToggleLifecycle = { lifecycleConfirm = product }
                    )
                }
                if (products.size < total) {
                    item {
                        if (loadingMore) InlineLoading()
                        else TextButton(onClick = { page++ }, modifier = Modifier.fillMaxWidth()) {
                            Text("Muat lebih banyak")
                        }
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        ProductFilterSheet(
            categories = categoryList.filter { it.isActive },
            stockFilter = stockFilter,
            lifecycleFilter = lifecycleFilter,
            categoryId = categoryId,
            sort = sort,
            canManage = canManage,
            onStock = { stockFilter = it },
            onLifecycle = { lifecycleFilter = it },
            onCategory = { categoryId = it },
            onSort = { sort = it },
            onManageCategories = { filtersOpen = false; categoriesOpen = true },
            onDismiss = { filtersOpen = false }
        )
    }

    if (editorOpen) {
        ProductEditor(
            existing = editorTarget,
            categories = categoryList,
            onDismiss = { editorOpen = false; editorTarget = null },
            onSave = { product ->
                val previous = editorTarget
                val result = AppGraph.products.save(product, userId)
                if (result.ok) {
                    if (previous != null && product.photo != previous.photo && !previous.photo.isNullOrBlank()) {
                        PhotoStorage.deleteManaged(previous.photo)
                    }
                    feedback?.success(if (previous == null) "Produk tersimpan" else "Produk diperbarui")
                    reloadPageZero()
                } else feedback?.error(result.error ?: "Produk gagal disimpan")
                result.ok
            }
        )
    }

    adjustProduct?.let { product ->
        StockAdjustSheet(
            product = product,
            userId = userId,
            onDismiss = { adjustProduct = null },
            onSaved = { message -> adjustProduct = null; feedback?.success(message); reloadPageZero() }
        )
    }

    if (categoriesOpen) {
        CategoryManagerSheet(
            categories = categoryList,
            userId = userId,
            onDismiss = { categoriesOpen = false },
            onMessage = { ok, message -> if (ok) feedback?.success(message) else feedback?.error(message) }
        )
    }

    lifecycleConfirm?.let { product ->
        ConfirmActionDialog(
            title = if (product.isActive) "Nonaktifkan produk?" else "Aktifkan produk?",
            message = if (product.isActive) {
                "\"${product.name}\" tidak akan muncul di Kasir. Data historis dan stok tetap tersimpan."
            } else {
                "\"${product.name}\" akan tersedia kembali di Kasir."
            },
            confirmLabel = if (product.isActive) "Nonaktifkan" else "Aktifkan",
            tone = if (product.isActive) Tone.WARNING else Tone.SUCCESS,
            onDismiss = { lifecycleConfirm = null },
            onConfirm = {
                lifecycleConfirm = null
                scope.launch {
                    val result = AppGraph.products.setActive(product.id, !product.isActive, userId)
                    if (result.ok) {
                        feedback?.success(if (product.isActive) "Produk dinonaktifkan" else "Produk diaktifkan")
                        reloadPageZero()
                    } else feedback?.error(result.error ?: "Perubahan status gagal")
                }
            }
        )
    }

    importPreview?.let { preview ->
        ExcelImportSheet(
            preview = preview,
            onDismiss = { importPreview = null },
            onConfirm = { duplicate, category ->
                scope.launch {
                    val result = excel.import(preview, duplicate, category, userId)
                    importPreview = null
                    feedback?.success(
                        "Import selesai • ${result.imported} baru, ${result.updated} diperbarui, " +
                            "${result.skipped} dilewati, ${result.failed} gagal"
                    )
                    reloadPageZero()
                }
            }
        )
    }
}

/** Structured product row; on expanded widths it reads like a table line. */
@Composable
private fun ProductRow(
    product: ProductEntity,
    canManage: Boolean,
    dense: Boolean,
    onEdit: () -> Unit,
    onAdjust: () -> Unit,
    onToggleLifecycle: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val outOfStock = product.trackInventory && product.stockQty <= 0
    val lowStock = product.trackInventory && !outOfStock && product.stockQty <= product.lowStockAlert
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = Radius.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canManage, onClick = onEdit)
    ) {
        Row(
            Modifier.padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            ProductThumbnail(product)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(product.sku, product.barcode).filter { it.isNotBlank() }
                        .joinToString(" • ").ifBlank { "Tanpa SKU/barcode" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (!product.isActive) StatusBadge("Nonaktif", Tone.NEUTRAL)
                    when {
                        outOfStock -> StatusBadge("Stok habis", Tone.DANGER)
                        lowStock -> StatusBadge("Stok menipis", Tone.WARNING)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    product.posSellPrice.takeIf { it > 0 } ?: product.sellPrice,
                    style = MaterialTheme.typography.bodyLarge,
                    weight = FontWeight.SemiBold
                )
                Text(
                    if (product.trackInventory) "${product.stockQty} ${product.uom}" else "Tanpa stok",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canManage) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(Touch.control)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Aksi untuk ${product.name}")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit produk") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Adjustment stok") },
                            leadingIcon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                            onClick = { menu = false; onAdjust() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (product.isActive) "Nonaktifkan" else "Aktifkan") },
                            leadingIcon = {
                                Icon(
                                    if (product.isActive) Icons.Default.ToggleOff else Icons.Default.ToggleOn,
                                    contentDescription = null
                                )
                            },
                            onClick = { menu = false; onToggleLifecycle() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductThumbnail(product: ProductEntity) {
    val path = product.photo
    if (!path.isNullOrBlank() && File(path).exists()) {
        LocalProductPhoto(path, product.name, Modifier.size(44.dp).clip(Radius.control))
    } else {
        Box(
            Modifier
                .size(44.dp)
                .clip(Radius.control)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun LocalProductPhoto(path: String, description: String, modifier: Modifier = Modifier) {
    // Bounded decode: read dimensions first, then downsample — a large legacy/camera
    // file must not OOM the product list when it renders a thumbnail.
    val bitmap = remember(path) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@remember null
        val target = 512
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > target) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProductFilterSheet(
    categories: List<CategoryEntity>,
    stockFilter: String,
    lifecycleFilter: String,
    categoryId: Long?,
    sort: String,
    canManage: Boolean,
    onStock: (String) -> Unit,
    onLifecycle: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onSort: (String) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            Text("Filter Produk", style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Status stok")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf("ALL", "LOW", "OUT").forEach { code ->
                        FilterChip(
                            selected = stockFilter == code,
                            onClick = { onStock(code) },
                            label = { Text(Labels.stockFilter(code)) },
                            shape = Radius.control,
                            modifier = Modifier.heightIn(min = Touch.min)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Status produk")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf("ACTIVE", "INACTIVE", "ALL").forEach { code ->
                        FilterChip(
                            selected = lifecycleFilter == code,
                            onClick = { onLifecycle(code) },
                            label = { Text(Labels.lifecycleFilter(code)) },
                            shape = Radius.control,
                            modifier = Modifier.heightIn(min = Touch.min)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader(
                    "Kategori",
                    trailing = {
                        if (canManage) TextButton(onClick = onManageCategories) { Text("Kelola") }
                    }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = categoryId == null,
                        onClick = { onCategory(null) },
                        label = { Text("Semua") },
                        shape = Radius.control
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = categoryId == category.id,
                            onClick = { onCategory(category.id) },
                            label = { Text(category.name, maxLines = 1) },
                            shape = Radius.control
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Urutkan")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(
                        "name_asc" to "Nama A–Z",
                        "name_desc" to "Nama Z–A",
                        "price_asc" to "Harga terendah",
                        "price_desc" to "Harga tertinggi",
                        "stock_asc" to "Stok terendah",
                        "stock_desc" to "Stok tertinggi"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = sort == code,
                            onClick = { onSort(code) },
                            label = { Text(label) },
                            shape = Radius.control
                        )
                    }
                }
            }

            Button(
                onClick = onDismiss,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text("Terapkan", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ------------------------------------------------------------------
// Product editor — full-screen on phone, side sheet on tablet.
// No longer a cramped AlertDialog with 20 fields.
// ------------------------------------------------------------------

private data class ProductDraft(
    val id: Long = 0,
    var name: String = "",
    var alternative: String = "",
    var categoryId: Long? = null,
    var categoryName: String = "",
    var brand: String = "",
    var sku: String = "",
    var barcode: String = "",
    var buy: String = "0",
    var market: String = "0",
    var sell: String = "0",
    var pos: String = "0",
    var dynamic: Boolean = false,
    var track: Boolean = true,
    var stock: String = "0",
    var minimum: String = "5",
    var uom: String = "PCS",
    var uomName: String = "Pieces",
    var converter: String = "1",
    var weight: String = "0",
    var loyalty: String = "0",
    var description: String = "",
    var notes: String = "",
    var published: Boolean = true,
    var hidden: Boolean = false,
    var taxFree: Boolean = false,
    var nonService: Boolean = false,
    var photo: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

private fun ProductEntity.toDraft(categories: List<CategoryEntity>) = ProductDraft(
    id, name, alternativeName, categoryId,
    categories.firstOrNull { it.id == categoryId }?.name.orEmpty(),
    brand, sku, barcode, buyPrice.toString(), marketPrice.toString(), sellPrice.toString(),
    posSellPrice.toString(), dynamicPriceEnabled, trackInventory, stockQty.toString(),
    lowStockAlert.toString(), uom, uomName, uomConverter.toString(), weightKg.toString(),
    loyaltyPoints.toString(), description, notes, published, posHidden, taxFreeItem,
    nonServiceCharge, photo, createdAt
)

/** Field-level validation returning the first offending label, or null when valid. */
private fun ProductDraft.validationError(): String? {
    listOf(
        "Harga beli" to buy,
        "Harga pasar" to market,
        "Harga jual" to sell,
        "Harga jual POS" to pos,
        "Loyalty points" to loyalty
    ).forEach { (label, raw) ->
        if (Money.parseOrNull(raw, allowBlank = true) == null) return "$label tidak valid"
    }
    if (Money.parseOrNull(stock, allowBlank = true) == null) return "Stok tidak valid"
    if (Money.parseOrNull(minimum, allowBlank = true) == null) return "Minimum stok tidak valid"
    return null
}

private fun ProductDraft.toEntity(): ProductEntity = ProductEntity(
    id = id,
    name = name.trim(),
    alternativeName = alternative.trim(),
    categoryId = categoryId,
    brand = brand.trim(),
    sku = sku.trim(),
    barcode = barcode.trim(),
    buyPrice = Money.parseOrNull(buy, allowBlank = true) ?: 0,
    marketPrice = Money.parseOrNull(market, allowBlank = true) ?: 0,
    sellPrice = Money.parseOrNull(sell, allowBlank = true) ?: 0,
    posSellPrice = Money.parseOrNull(pos, allowBlank = true) ?: 0,
    dynamicPriceEnabled = dynamic,
    trackInventory = track,
    stockQty = Money.parseOrNull(stock, allowBlank = true) ?: 0,
    lowStockAlert = Money.parseOrNull(minimum, allowBlank = true) ?: 0,
    uom = uom.trim().ifBlank { "PCS" },
    uomName = uomName.trim().ifBlank { "Pieces" },
    uomConverter = converter.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 1.0,
    weightKg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
    loyaltyPoints = Money.parseOrNull(loyalty, allowBlank = true) ?: 0,
    description = description.trim(),
    notes = notes.trim(),
    published = published,
    posHidden = hidden,
    taxFreeItem = taxFree,
    nonServiceCharge = nonService,
    photo = photo,
    createdAt = createdAt
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductEditor(
    existing: ProductEntity?,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: suspend (ProductEntity) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val widthClass = currentWidthClass
    var draft by remember(existing?.id, categories) { mutableStateOf(existing?.toDraft(categories) ?: ProductDraft()) }
    val originalPhoto = existing?.photo
    var selectCategory by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var saveSucceeded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    fun replaceDraftPhoto(path: String) {
        val previous = draft.photo
        if (previous != null && previous != originalPhoto && previous != path) PhotoStorage.deleteManaged(previous)
        draft = draft.copy(photo = path)
    }
    fun startCamera(capture: (android.net.Uri) -> Unit) {
        val target = PhotoStorage.createCameraTarget(context)
        cameraFile = target.first
        capture(target.second)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val raw = cameraFile
        if (ok && raw != null) {
            // Normalize the raw sensor capture (bounds check + resize + re-encode), then
            // discard the uncontrolled original so it never persists as the product photo.
            val normalized = PhotoStorage.importFromUri(
                context,
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", raw)
            )
            if (normalized != null) replaceDraftPhoto(normalized)
            raw.delete()
        } else raw?.delete()
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) PhotoStorage.importFromUri(context, uri)?.let(::replaceDraftPhoto)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera(cameraLauncher::launch)
    }

    fun submit() {
        val invalid = draft.validationError()
        if (invalid != null) {
            inlineError = invalid
            return
        }
        if (saving) return
        inlineError = null
        scope.launch {
            saving = true
            val ok = try { onSave(draft.toEntity()) } finally { saving = false }
            if (ok) {
                saveSucceeded = true
                onDismiss()
            }
        }
    }

    // Orphan cleanup policy (explicit ownership):
    //  - new photo selected + user CANCELS  -> delete the unreferenced managed draft;
    //  - new photo selected + SAVE FAILS    -> editor stays open, file stays for retry;
    //  - new photo selected + SAVE SUCCEEDS -> new file remains and DB references it;
    //    the old referenced file is deleted only after the successful DB commit.
    DisposableEffect(Unit) {
        onDispose {
            if (!saveSucceeded) {
                val newPhoto = draft.photo
                if (newPhoto != null && newPhoto != originalPhoto) PhotoStorage.deleteManaged(newPhoto)
            }
        }
    }

    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (existing == null) "Tambah Produk" else "Edit Produk",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (existing != null) {
                        Text(
                            existing.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Tutup editor") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(Space.lg)
            ) {
                FormSection("Foto produk") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        val photo = draft.photo
                        if (photo != null && File(photo).exists()) {
                            Box {
                                LocalProductPhoto(
                                    photo,
                                    "Foto produk",
                                    Modifier.fillMaxWidth().height(160.dp).clip(Radius.card)
                                )
                                IconButton(
                                    onClick = {
                                        if (photo != originalPhoto) PhotoStorage.deleteManaged(photo)
                                        draft = draft.copy(photo = null)
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) { Icon(Icons.Default.Close, contentDescription = "Hapus foto") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            OutlinedButton(
                                onClick = { galleryLauncher.launch("image/*") },
                                shape = Radius.field,
                                modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Space.xs))
                                Text("Galeri")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) startCamera(cameraLauncher::launch)
                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                shape = Radius.field,
                                modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Space.xs))
                                Text("Kamera")
                            }
                        }
                    }
                }

                FormSection("Informasi dasar") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        EditorField("Nama produk *", draft.name) { draft = draft.copy(name = it) }
                        EditorField("Nama alternatif", draft.alternative) { draft = draft.copy(alternative = it) }
                        OutlinedButton(
                            onClick = { selectCategory = true },
                            shape = Radius.field,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                        ) {
                            Text(
                                if (draft.categoryName.isBlank()) "Pilih kategori (opsional)"
                                else "Kategori: ${draft.categoryName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        EditorField("Brand", draft.brand) { draft = draft.copy(brand = it) }
                        EditorField("SKU (kosong = otomatis)", draft.sku) { draft = draft.copy(sku = it) }
                        EditorField("Barcode", draft.barcode) { draft = draft.copy(barcode = it) }
                    }
                }

                FormSection("Harga") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        EditorField("Harga beli", draft.buy, numeric = true) { draft = draft.copy(buy = it) }
                        EditorField("Harga pasar", draft.market, numeric = true) { draft = draft.copy(market = it) }
                        EditorField("Harga jual", draft.sell, numeric = true) { draft = draft.copy(sell = it) }
                        EditorField("Harga jual POS", draft.pos, numeric = true) { draft = draft.copy(pos = it) }
                        EditorToggle("Harga dinamis", draft.dynamic) { draft = draft.copy(dynamic = it) }
                    }
                }

                FormSection("Inventory") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        EditorToggle("Lacak stok produk ini", draft.track) { draft = draft.copy(track = it) }
                        if (draft.track) {
                            EditorField(
                                if (existing == null) "Stok awal" else "Stok saat ini",
                                draft.stock,
                                numeric = true
                            ) { draft = draft.copy(stock = it) }
                            EditorField("Batas stok menipis", draft.minimum, numeric = true) { draft = draft.copy(minimum = it) }
                        }
                        EditorField("Satuan", draft.uom) { draft = draft.copy(uom = it) }
                        EditorField("Nama satuan", draft.uomName) { draft = draft.copy(uomName = it) }
                        EditorField("Konversi satuan", draft.converter, numeric = true) { draft = draft.copy(converter = it) }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TextButton(onClick = { advanced = !advanced }) {
                        Icon(
                            if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Space.xs))
                        Text(if (advanced) "Sembunyikan informasi tambahan" else "Informasi tambahan")
                    }
                    if (advanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            EditorField("Berat (kg)", draft.weight, numeric = true) { draft = draft.copy(weight = it) }
                            EditorField("Loyalty points", draft.loyalty, numeric = true) { draft = draft.copy(loyalty = it) }
                            EditorField("Deskripsi", draft.description, single = false) { draft = draft.copy(description = it) }
                            EditorField("Catatan", draft.notes, single = false) { draft = draft.copy(notes = it) }
                            EditorToggle("Metadata published", draft.published) { draft = draft.copy(published = it) }
                            EditorToggle("Sembunyikan dari Kasir", draft.hidden) { draft = draft.copy(hidden = it) }
                            EditorToggle("Bebas pajak", draft.taxFree) { draft = draft.copy(taxFree = it) }
                            EditorToggle("Tanpa service charge", draft.nonService) { draft = draft.copy(nonService = it) }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .padding(horizontal = Space.lg, vertical = Space.md)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                inlineError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = Radius.field,
                        modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                    ) { Text("Batal") }
                    Button(
                        onClick = ::submit,
                        enabled = draft.name.trim().isNotEmpty() && !saving,
                        shape = Radius.field,
                        modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                    ) { Text(if (saving) "Menyimpan…" else "Simpan Produk", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (widthClass.isExpanded) {
        // Tablet: full-height side sheet, POS list remains visible behind it.
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxHeight().width(560.dp),
            shape = Radius.panel,
            title = null,
            text = { Box(Modifier.fillMaxSize()) { body() } },
            confirmButton = {}
        )
    } else {
        // Phone: full-screen editor, not a cramped dialog.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { body() }
        }
    }

    if (selectCategory) {
        CategoryPickerDialog(
            categories = categories.filter { it.isActive },
            onDismiss = { selectCategory = false },
            onSelect = { category ->
                draft = draft.copy(categoryId = category?.id, categoryName = category?.name.orEmpty())
                selectCategory = false
            }
        )
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    numeric: Boolean = false,
    single: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = single,
        minLines = if (single) 1 else 3,
        shape = Radius.field,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (single) ImeAction.Next else ImeAction.Default
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EditorToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Touch.min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun CategoryPickerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSelect: (CategoryEntity?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radius.panel,
        title = { Text("Pilih Kategori", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    Text(
                        "Tanpa kategori",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Touch.min)
                            .clickable { onSelect(null) }
                            .padding(vertical = Space.sm)
                    )
                }
                items(categories, key = { it.id }) { category ->
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Touch.min)
                            .clickable { onSelect(category) }
                            .padding(vertical = Space.sm)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

/** Stock adjustment sheet. Locked semantics: ADD>0, REMOVE>0, SET>=0, reason required. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StockAdjustSheet(
    product: ProductEntity,
    userId: Long,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var mode by remember { mutableStateOf("ADD") }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Adjustment Stok", style = MaterialTheme.typography.titleMedium)
            Text(
                "${product.name} • stok saat ini ${product.stockQty} ${product.uom}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf("ADD", "REMOVE", "SET").forEach { code ->
                    FilterChip(
                        selected = mode == code,
                        onClick = { mode = code; error = null },
                        label = { Text(Labels.adjustmentMode(code)) },
                        shape = Radius.control,
                        modifier = Modifier.heightIn(min = Touch.min)
                    )
                }
            }
            OutlinedTextField(
                value = amount,
                // Stock is a count, never currency: digits only, no money parser.
                onValueChange = { value -> amount = value.filter(Char::isDigit); error = null },
                label = { Text(if (mode == "SET") "Stok baru" else "Jumlah") },
                singleLine = true,
                shape = Radius.field,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it; error = null },
                label = { Text("Alasan (wajib)") },
                shape = Radius.field,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val validation = OperationalInputRules.stockAdjustment(mode, amount, reason)
                    val quantity = validation.amount
                    if (validation.error != null || quantity == null) error = validation.error
                    else {
                        saving = true
                        scope.launch {
                            val ok = AppGraph.products.adjustStock(product, mode, quantity, reason.trim(), userId)
                            saving = false
                            if (ok) onSaved("Adjustment stok tersimpan")
                            else error = "Adjustment ditolak; periksa stok tersedia"
                        }
                    }
                },
                enabled = !saving,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text(if (saving) "Menyimpan…" else "Simpan Adjustment", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagerSheet(
    categories: List<CategoryEntity>,
    userId: Long,
    onDismiss: () -> Unit,
    onMessage: (Boolean, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = Space.xl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Kelola Kategori", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                name, { name = it },
                label = { Text("Nama kategori") },
                singleLine = true,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                description, { description = it },
                label = { Text("Deskripsi") },
                singleLine = true,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(
                    onClick = {
                        scope.launch {
                            val target = if (editingId != null) {
                                categories.firstOrNull { it.id == editingId }
                                    ?.copy(name = name.trim(), description = description.trim())
                            } else CategoryEntity(name = name.trim(), description = description.trim())
                            if (target == null) {
                                onMessage(false, "Kategori tidak ditemukan")
                                return@launch
                            }
                            val result = AppGraph.products.saveCategory(target, userId)
                            if (result.ok) {
                                onMessage(true, if (editingId != null) "Kategori diperbarui" else "Kategori tersimpan")
                                name = ""; description = ""; editingId = null
                            } else onMessage(false, result.error ?: "Kategori gagal disimpan")
                        }
                    },
                    enabled = name.isNotBlank(),
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text(if (editingId != null) "Simpan Perubahan" else "Tambah Kategori") }
                if (editingId != null) {
                    OutlinedButton(
                        onClick = { editingId = null; name = ""; description = "" },
                        shape = Radius.field,
                        modifier = Modifier.heightIn(min = Touch.control)
                    ) { Text("Batal") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (categories.isEmpty()) {
                Text(
                    "Belum ada kategori selain bawaan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(categories, key = { it.id }) { category ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(category.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                if (!category.isActive) StatusBadge("Nonaktif", Tone.NEUTRAL)
                            }
                            TextButton(onClick = {
                                editingId = category.id; name = category.name; description = category.description
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                scope.launch {
                                    AppGraph.products.setCategoryActive(category.id, !category.isActive, userId)
                                    onMessage(true, if (category.isActive) "Kategori dinonaktifkan" else "Kategori diaktifkan")
                                }
                            }) { Text(if (category.isActive) "Nonaktifkan" else "Aktifkan") }
                            TextButton(onClick = {
                                scope.launch {
                                    val result = AppGraph.products.deleteCategorySafe(category.id, userId)
                                    onMessage(result.first, result.second)
                                }
                            }) { Text("Hapus") }
                        }
                    }
                }
            }
        }
    }
}

/** Excel import flow: preview → summary → policy → import, with readable row errors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcelImportSheet(
    preview: ProductExcelService.Preview,
    onDismiss: () -> Unit,
    onConfirm: (ProductExcelService.DuplicatePolicy, ProductExcelService.MissingCategoryPolicy) -> Unit
) {
    var duplicate by remember { mutableStateOf(ProductExcelService.DuplicatePolicy.SKIP) }
    var categoryPolicy by remember { mutableStateOf(ProductExcelService.MissingCategoryPolicy.USE_OTHERS) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = Space.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Preview Import Excel", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sheet \"${preview.sheet}\" • ${preview.total} baris data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatusBadge("${preview.valid} valid", Tone.SUCCESS)
                StatusBadge("${preview.duplicates} duplikat", Tone.WARNING)
                StatusBadge("${preview.errors} error", if (preview.errors > 0) Tone.DANGER else Tone.NEUTRAL)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                SectionHeader("Kebijakan data duplikat")
                ProductExcelService.DuplicatePolicy.entries.forEach { policy ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Touch.min).clickable { duplicate = policy },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = duplicate == policy, onClick = { duplicate = policy })
                        Text(
                            when (policy) {
                                ProductExcelService.DuplicatePolicy.SKIP -> "Lewati baris duplikat (disarankan)"
                                ProductExcelService.DuplicatePolicy.UPDATE -> "Perbarui produk yang sudah ada"
                                ProductExcelService.DuplicatePolicy.CREATE_NEW -> "Buat produk baru terpisah"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                SectionHeader("Kategori belum ada")
                ProductExcelService.MissingCategoryPolicy.entries.forEach { policy ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Touch.min).clickable { categoryPolicy = policy },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = categoryPolicy == policy, onClick = { categoryPolicy = policy })
                        Text(
                            if (policy == ProductExcelService.MissingCategoryPolicy.CREATE_AUTOMATICALLY)
                                "Buat kategori baru otomatis" else "Masukkan ke kategori Lainnya",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                SectionHeader("Contoh 10 baris pertama")
                preview.sample.forEach { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                        Text(
                            "Baris ${row.excelRow} • ${row.values["name"].orEmpty().ifBlank { "(nama kosong)" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        when {
                            row.errors.isNotEmpty() -> row.errors.forEach { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            row.duplicate -> StatusBadge("Duplikat SKU/barcode", Tone.WARNING)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(bottom = Space.lg)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text("Batal") }
                Button(
                    onClick = { onConfirm(duplicate, categoryPolicy) },
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text("Import Sekarang", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
