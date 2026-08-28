package com.trapezo.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CategoryEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.domain.model.OperationalInputRules
import com.trapezo.pos.excel.ProductExcelService
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
    var message by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf<ProductEntity?>(null) }
    var createProduct by remember { mutableStateOf(false) }
    var adjustProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<ProductExcelService.Preview?>(null) }
    val excel = remember { ProductExcelService(AppGraph.db) }

    fun reloadPageZero() {
        page = 0
        reloadKey++
    }

    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }
    LaunchedEffect(debouncedQuery, sort, stockFilter, lifecycleFilter, categoryId) {
        page = 0
    }
    LaunchedEffect(debouncedQuery, sort, stockFilter, lifecycleFilter, categoryId, page, reloadKey) {
        val version = ++requestVersion
        val requestedPage = page
        val result = AppGraph.products.filteredPage(
            debouncedQuery, categoryId, lifecycleFilter, false, stockFilter, sort, requestedPage
        )
        if (version != requestVersion) return@LaunchedEffect
        products = if (requestedPage == 0) result.first.distinctBy { it.id }
        else (products + result.first).distinctBy { it.id }
        total = result.second
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openInputStream(uri)?.use { importPreview = excel.preview(it) }
                    ?: run { message = "File Excel tidak bisa dibuka" }
            } catch (e: Exception) { message = e.message ?: "Gagal membaca Excel" }
        }
    }
    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { excel.writeTemplate(it) }
            message = "Template Excel kosong berhasil disimpan"
        } catch (e: Exception) { message = "Template gagal dibuat: ${e.message}" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { excel.export(it) }
                message = "Export produk berhasil disimpan"
            } catch (e: Exception) { message = "Export gagal: ${e.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Produk", fontWeight = FontWeight.Bold) },
            actions = {
                if (canManage) {
                    IconButton(onClick = { templateLauncher.launch("TrapezoPOS_Template_Produk.xlsx") }) { Icon(Icons.Default.FileDownload, "Download template Excel") }
                    IconButton(onClick = { exportLauncher.launch("TrapezoPOS_Produk.xlsx") }) { Icon(Icons.Default.FileUpload, "Export Excel") }
                    IconButton(onClick = { importLauncher.launch(arrayOf(XLSX_MIME, "application/vnd.ms-excel")) }) { Icon(Icons.Default.FileUpload, "Import Excel") }
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (message != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(message!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = { message = null }) { Text("Tutup") }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Cari nama, SKU, atau barcode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "Semua", "LOW" to "Stok rendah", "OUT" to "Stok habis").forEach { (id, label) ->
                    FilterChip(selected = stockFilter == id, onClick = { stockFilter = id; page = 0 }, label = { Text(label) })
                }
                SortMenu(sort) { sort = it; page = 0 }
                CategoryFilterMenu(categoryList.filter { it.isActive }, categoryId) { categoryId = it; page = 0 }
                LifecycleFilterMenu(lifecycleFilter) { lifecycleFilter = it; page = 0 }
                if (canManage) AssistChip(onClick = { categoriesOpen = true }, label = { Text("Kategori") }, leadingIcon = { Icon(Icons.Default.MoreVert, null) })
            }
            if (canManage) Button(onClick = { createProduct = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("TAMBAH PRODUK") }
            if (products.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Belum ada produk. Tambahkan manual atau import Excel.") }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(products, key = { it.id }) { product ->
                        ProductRow(product, canManage, onEdit = { showForm = product }, onAdjust = { adjustProduct = product }, onDeactivate = {
                            scope.launch {
                                val result = AppGraph.products.setActive(product.id, !product.isActive, userId)
                                if (result.ok) {
                                    message = if (product.isActive) "Produk dinonaktifkan" else "Produk diaktifkan"
                                    reloadPageZero()
                                } else message = result.error
                            }
                        })
                    }
                    item {
                        if (products.size < total) TextButton(onClick = { page++ }, Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
                    }
                }
            }
        }
    }
    if (createProduct) ProductEditorDialog(null, categoryList, onDismiss = { createProduct = false }, onSave = { product ->
        scope.launch {
            val r = AppGraph.products.save(product, userId)
            if (r.ok) { createProduct = false; message = "Produk tersimpan"; reloadPageZero() } else message = r.error
        }
    })
    showForm?.let { existing -> ProductEditorDialog(existing, categoryList, onDismiss = { showForm = null }, onSave = { product ->
        scope.launch {
            val r = AppGraph.products.save(product, userId)
            if (r.ok) {
                // The dialog committed; the photo is now referenced. Clear the draft's
                // orphan-cleanup obligation for the replacement, and delete the old one.
                if (product.photo != existing.photo && !product.photo.isNullOrBlank()) {
                    // replacement is now live; dialog will dispose without deleting it
                }
                if (product.photo != existing.photo && !existing.photo.isNullOrBlank()) {
                    PhotoStorage.deleteManaged(existing.photo)
                }
                showForm = null; message = "Produk diperbarui"; reloadPageZero()
            } else message = r.error
        }
    }) }
    adjustProduct?.let { product -> StockAdjustDialog(product, userId, onDismiss = { adjustProduct = null }, onSaved = { text -> adjustProduct = null; message = text; reloadPageZero() }) }
    if (categoriesOpen) CategoryManagerDialog(categoryList, userId, onDismiss = { categoriesOpen = false }, onMessage = { message = it })
    importPreview?.let { preview -> ExcelPreviewDialog(preview, onDismiss = { importPreview = null }, onConfirm = { duplicate, category ->
        scope.launch {
            val r = excel.import(preview, duplicate, category, userId)
            importPreview = null
            message = "Import selesai: ${r.imported} baru, ${r.updated} diperbarui, ${r.skipped} dilewati, ${r.failed} gagal"
            reloadPageZero()
        }
    }) }
}

@Composable
private fun SortMenu(selected: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Urutkan") }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("name_asc" to "Nama A–Z", "name_desc" to "Nama Z–A", "price_asc" to "Harga terendah", "price_desc" to "Harga tertinggi", "stock_asc" to "Stok terendah", "stock_desc" to "Stok tertinggi").forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(id); open = false })
            }
        }
    }
}

@Composable
private fun CategoryFilterMenu(categories: List<CategoryEntity>, selected: Long?, onSelected: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(categories.firstOrNull { it.id == selected }?.name ?: "Kategori: Semua") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Semua kategori") }, onClick = { onSelected(null); open = false })
            categories.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelected(c.id); open = false }) }
        }
    }
}

@Composable
private fun LifecycleFilterMenu(selected: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (selected) {
        "INACTIVE" -> "Nonaktif"
        "ALL" -> "Semua"
        else -> "Aktif"
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Status: $label") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("ACTIVE" to "Aktif", "INACTIVE" to "Nonaktif", "ALL" to "Semua").forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(id); open = false })
            }
        }
    }
}

@Composable
private fun LocalProductPhoto(path: String, description: String, modifier: Modifier = Modifier) {
    // Bounded decode: read dimensions first, then downsample — a large legacy/camera
    // file must not OOM the product list when it renders a thumbnail.
    val bitmap = remember(path) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@remember null
        val target = 512
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > target) sample *= 2
        BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
    if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun ProductRow(product: ProductEntity, canManage: Boolean, onEdit: () -> Unit, onAdjust: () -> Unit, onDeactivate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        if (canManage) Modifier.fillMaxWidth().clickable(onClick = onEdit)
        else Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!product.photo.isNullOrBlank() && File(product.photo).exists()) {
                LocalProductPhoto(product.photo, product.name, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
            } else Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.Inventory, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.SemiBold)
                Text(listOf(product.sku, product.barcode).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Tanpa SKU/barcode" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Money.fmt(product.posSellPrice.takeIf { it > 0 } ?: product.sellPrice)}  •  Stok ${product.stockQty} ${product.uom}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                if (!product.isActive) Text("NONAKTIF", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (canManage) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Aksi produk") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Adjustment stok") }, leadingIcon = { Icon(Icons.Default.Inventory, null) }, onClick = { menu = false; onAdjust() })
                    DropdownMenuItem(text = { Text(if (product.isActive) "Nonaktifkan" else "Aktifkan") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDeactivate() })
                }
            }
        }
    }
}

private data class ProductDraft(
    val id: Long = 0, var name: String = "", var alternative: String = "", var categoryId: Long? = null, var categoryName: String = "",
    var brand: String = "", var sku: String = "", var barcode: String = "", var buy: String = "0", var market: String = "0", var sell: String = "0", var pos: String = "0",
    var dynamic: Boolean = false, var track: Boolean = true, var stock: String = "0", var minimum: String = "5", var uom: String = "PCS", var uomName: String = "Pieces", var converter: String = "1",
    var weight: String = "0", var loyalty: String = "0", var description: String = "", var notes: String = "", var published: Boolean = true, var hidden: Boolean = false,
    var taxFree: Boolean = false, var nonService: Boolean = false, var photo: String? = null, val createdAt: Long = System.currentTimeMillis()
)
private fun ProductEntity.toDraft(categories: List<CategoryEntity>) = ProductDraft(id, name, alternativeName, categoryId, categories.firstOrNull { it.id == categoryId }?.name.orEmpty(), brand, sku, barcode, buyPrice.toString(), marketPrice.toString(), sellPrice.toString(), posSellPrice.toString(), dynamicPriceEnabled, trackInventory, stockQty.toString(), lowStockAlert.toString(), uom, uomName, uomConverter.toString(), weightKg.toString(), loyaltyPoints.toString(), description, notes, published, posHidden, taxFreeItem, nonServiceCharge, photo, createdAt)

/** Validates all money/qty fields; returns error message or null. */
private fun ProductDraft.validationError(): String? {
    listOf("Harga beli" to buy, "Harga pasar" to market, "Harga jual" to sell, "Harga jual POS" to pos, "Loyalty points" to loyalty).forEach { (label, raw) ->
        if (Money.parseOrNull(raw, allowBlank = true) == null) return "$label tidak valid"
    }
    if (Money.parseOrNull(stock, allowBlank = true) == null) return "Stok tidak valid"
    if (Money.parseOrNull(minimum, allowBlank = true) == null) return "Minimum stok tidak valid"
    return null
}

private fun ProductDraft.toEntity(): ProductEntity = ProductEntity(id = id, name = name.trim(), alternativeName = alternative.trim(), categoryId = categoryId, brand = brand.trim(), sku = sku.trim(), barcode = barcode.trim(), buyPrice = Money.parseOrNull(buy, allowBlank = true) ?: 0, marketPrice = Money.parseOrNull(market, allowBlank = true) ?: 0, sellPrice = Money.parseOrNull(sell, allowBlank = true) ?: 0, posSellPrice = Money.parseOrNull(pos, allowBlank = true) ?: 0, dynamicPriceEnabled = dynamic, trackInventory = track, stockQty = Money.parseOrNull(stock, allowBlank = true) ?: 0, lowStockAlert = Money.parseOrNull(minimum, allowBlank = true) ?: 0, uom = uom.trim().ifBlank { "PCS" }, uomName = uomName.trim().ifBlank { "Pieces" }, uomConverter = converter.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 1.0, weightKg = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0, loyaltyPoints = Money.parseOrNull(loyalty, allowBlank = true) ?: 0, description = description.trim(), notes = notes.trim(), published = published, posHidden = hidden, taxFreeItem = taxFree, nonServiceCharge = nonService, photo = photo, createdAt = createdAt)

@Composable
private fun ProductEditorDialog(existing: ProductEntity?, categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (ProductEntity) -> Unit) {
    val context = LocalContext.current
    var draft by remember(existing?.id, categories) { mutableStateOf(existing?.toDraft(categories) ?: ProductDraft()) }
    val originalPhoto = existing?.photo
    var selectCategory by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    fun startCamera(capture: (android.net.Uri) -> Unit) { val target = PhotoStorage.createCameraTarget(context); cameraFile = target.first; capture(target.second) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val raw = cameraFile
        if (ok && raw != null) {
            // Normalize the raw sensor capture (bounds check + resize + re-encode), then
            // discard the uncontrolled original so it never persists as the product photo.
            val normalized = PhotoStorage.importFromUri(context, androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", raw))
            if (normalized != null) draft = draft.copy(photo = normalized)
            raw.delete()
        } else {
            raw?.delete()
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) PhotoStorage.importFromUri(context, uri)?.let { draft = draft.copy(photo = it) } }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startCamera(cameraLauncher::launch) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Tambah produk" else "Edit produk") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (draft.photo != null && File(draft.photo!!).exists()) LocalProductPhoto(draft.photo!!, "Foto produk", Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text(" Galeri") }
                    OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(cameraLauncher::launch) else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, Modifier.weight(1f)) { Icon(Icons.Default.PhotoCamera, null); Text(" Kamera") }
                }
                Text("Informasi dasar", fontWeight = FontWeight.SemiBold)
                Field("Nama produk *", draft.name) { draft = draft.copy(name = it) }
                Field("Nama alternatif", draft.alternative) { draft = draft.copy(alternative = it) }
                OutlinedButton(onClick = { selectCategory = true }, Modifier.fillMaxWidth()) { Text(if (draft.categoryName.isBlank()) "Pilih kategori (opsional)" else "Kategori: ${draft.categoryName}") }
                Field("Brand", draft.brand) { draft = draft.copy(brand = it) }
                Field("SKU (kosong = otomatis)", draft.sku) { draft = draft.copy(sku = it) }
                Field("Barcode", draft.barcode) { draft = draft.copy(barcode = it) }
                Text("Harga", fontWeight = FontWeight.SemiBold)
                Field("Harga beli", draft.buy, numeric = true) { draft = draft.copy(buy = it) }
                Field("Harga pasar", draft.market, numeric = true) { draft = draft.copy(market = it) }
                Field("Harga jual", draft.sell, numeric = true) { draft = draft.copy(sell = it) }
                Field("Harga jual POS", draft.pos, numeric = true) { draft = draft.copy(pos = it) }
                Toggle("Harga dinamis", draft.dynamic) { draft = draft.copy(dynamic = it) }
                Text("Inventory", fontWeight = FontWeight.SemiBold)
                Toggle("Tracking inventory", draft.track) { draft = draft.copy(track = it) }
                if (draft.track) { Field("Stok awal", draft.stock, numeric = true) { draft = draft.copy(stock = it) }; Field("Minimum stok", draft.minimum, numeric = true) { draft = draft.copy(minimum = it) } }
                Field("Satuan", draft.uom) { draft = draft.copy(uom = it) }
                Field("Nama satuan", draft.uomName) { draft = draft.copy(uomName = it) }
                Field("Konversi satuan", draft.converter, numeric = true) { draft = draft.copy(converter = it) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Sembunyikan informasi tambahan" else "Tampilkan informasi tambahan") }
                if (advanced) {
                    Field("Berat (kg)", draft.weight, numeric = true) { draft = draft.copy(weight = it) }
                    Field("Loyalty points", draft.loyalty, numeric = true) { draft = draft.copy(loyalty = it) }
                    Field("Deskripsi", draft.description, single = false) { draft = draft.copy(description = it) }
                    Field("Catatan", draft.notes, single = false) { draft = draft.copy(notes = it) }
                    Toggle("Metadata published (tidak mengatur status aktif)", draft.published) { draft = draft.copy(published = it) }
                    Toggle("Sembunyikan dari POS", draft.hidden) { draft = draft.copy(hidden = it) }
                    Toggle("Bebas pajak", draft.taxFree) { draft = draft.copy(taxFree = it) }
                    Toggle("Tidak kena service charge", draft.nonService) { draft = draft.copy(nonService = it) }
                }
            }
        },
        confirmButton = { Button(onClick = { val invalid = draft.validationError(); if (invalid == null) onSave(draft.toEntity()) }, enabled = draft.name.trim().isNotEmpty()) { Text("SIMPAN") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )

    // Orphan cleanup: if the editor is dismissed after a new photo was imported (or the
    // camera temp was normalized) without saving, delete the new managed draft file so
    // unreferenced images do not accumulate. Never delete the original referenced photo.
    DisposableEffect(Unit) {
        onDispose {
            val newPhoto = draft.photo
            if (newPhoto != null && newPhoto != originalPhoto) {
                com.trapezo.pos.utils.PhotoStorage.deleteManaged(newPhoto)
            }
        }
    }
    if (selectCategory) AlertDialog(onDismissRequest = { selectCategory = false }, title = { Text("Pilih kategori") }, text = { LazyColumn { item { TextButton(onClick = { draft = draft.copy(categoryId = null, categoryName = ""); selectCategory = false }) { Text("Tanpa kategori") } }; items(categories.filter { it.isActive }) { c -> TextButton(onClick = { draft = draft.copy(categoryId = c.id, categoryName = c.name); selectCategory = false }, Modifier.fillMaxWidth()) { Text(c.name) } } } }, confirmButton = { TextButton(onClick = { selectCategory = false }) { Text("Tutup") } })
}

@Composable private fun Field(label: String, value: String, numeric: Boolean = false, single: Boolean = true, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = single, modifier = Modifier.fillMaxWidth())
@Composable private fun Toggle(label: String, value: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, set) }

@Composable
private fun StockAdjustDialog(product: ProductEntity, userId: Long, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    var mode by remember { mutableStateOf("ADD") }; var amount by remember { mutableStateOf("") }; var reason by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adjustment stok: ${product.name}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stok saat ini: ${product.stockQty} ${product.uom}")
        Row { listOf("ADD" to "Tambah", "REMOVE" to "Kurangi", "SET" to "Set stok").forEach { (id,label) -> FilterChip(selected = mode == id, onClick = { mode = id }, label = { Text(label) }) } }
        Field(if (mode == "SET") "Stok baru" else "Jumlah", amount, numeric = true) { amount = it }
        Field("Alasan wajib", reason, single = false) { reason = it }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = {
        val validation = OperationalInputRules.stockAdjustment(mode, amount, reason)
        val quantity = validation.amount
        if (validation.error != null || quantity == null) error = validation.error
        else scope.launch { val ok = AppGraph.products.adjustStock(product, mode, quantity, reason.trim(), userId); if(ok) onSaved("Adjustment stok tersimpan") else error = "Adjustment ditolak; periksa stok" }
    }) { Text("SIMPAN") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun CategoryManagerDialog(categories: List<CategoryEntity>, userId: Long, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope(); var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var editingId by remember { mutableStateOf<Long?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Kategori") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Nama kategori", name) { name = it }; Field("Deskripsi", desc) { desc = it }
        Button(onClick = {
            scope.launch {
                val target = if (editingId != null) categories.firstOrNull { it.id == editingId }?.copy(name = name.trim(), description = desc.trim()) else CategoryEntity(name = name, description = desc)
                if (target == null) { onMessage("Kategori tidak ditemukan"); return@launch }
                val r = AppGraph.products.saveCategory(target, userId)
                onMessage(r.error ?: if (editingId != null) "Kategori diperbarui" else "Kategori tersimpan")
                if (r.ok) { name = ""; desc = ""; editingId = null }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (editingId != null) "SIMPAN PERUBAHAN" else "TAMBAH KATEGORI") }
        if (editingId != null) TextButton(onClick = { editingId = null; name = ""; desc = "" }, modifier = Modifier.fillMaxWidth()) { Text("BATAL EDIT") }
        HorizontalDivider(); categories.forEach { c -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(if (c.isActive) c.name else "${c.name} (nonaktif)", color = if (c.isActive) Color.Unspecified else MaterialTheme.colorScheme.error); if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { editingId = c.id; name = c.name; desc = c.description }) { Text("Edit") }
            TextButton(onClick = { scope.launch { AppGraph.products.setCategoryActive(c.id, !c.isActive, userId); onMessage(if (c.isActive) "Kategori dinonaktifkan" else "Kategori diaktifkan") } }) { Text(if (c.isActive) "Nonaktif" else "Aktif") }
            TextButton(onClick = { scope.launch { val r = AppGraph.products.deleteCategorySafe(c.id, userId); onMessage(r.second) } }) { Text("Hapus") }
        } }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun ExcelPreviewDialog(preview: ProductExcelService.Preview, onDismiss: () -> Unit, onConfirm: (ProductExcelService.DuplicatePolicy, ProductExcelService.MissingCategoryPolicy) -> Unit) {
    var duplicate by remember { mutableStateOf(ProductExcelService.DuplicatePolicy.SKIP) }; var category by remember { mutableStateOf(ProductExcelService.MissingCategoryPolicy.USE_OTHERS) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Preview import Excel") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sheet: ${preview.sheet}"); Text("Total data: ${preview.total}\nValid: ${preview.valid}\nError: ${preview.errors}\nDuplikat SKU/barcode: ${preview.duplicates}")
        Text("Data duplikat", fontWeight = FontWeight.SemiBold); ProductExcelService.DuplicatePolicy.entries.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(p == duplicate, { duplicate = p }); Text(when(p) { ProductExcelService.DuplicatePolicy.SKIP -> "Lewati (default)"; ProductExcelService.DuplicatePolicy.UPDATE -> "Perbarui produk"; ProductExcelService.DuplicatePolicy.CREATE_NEW -> "Buat produk baru" }) } }
        Text("Kategori belum ada", fontWeight = FontWeight.SemiBold); ProductExcelService.MissingCategoryPolicy.entries.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(p == category, { category = p }); Text(if(p == ProductExcelService.MissingCategoryPolicy.CREATE_AUTOMATICALLY) "Buat kategori otomatis" else "Masukkan ke Lainnya") } }
        Text("Preview 10 baris", fontWeight = FontWeight.SemiBold); preview.sample.forEach { r -> Text("Baris ${r.excelRow}: ${r.values["name"].orEmpty()}${if (r.errors.isNotEmpty()) " — ${r.errors.joinToString()}" else if (r.duplicate) " — DUPLIKAT" else ""}", color = if(r.errors.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    } }, confirmButton = { Button(onClick = { onConfirm(duplicate, category) }) { Text("KONFIRMASI IMPORT") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}
