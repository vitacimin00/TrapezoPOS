package com.trapezo.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.InventoryMovementEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(userId: Long, canAdjust: Boolean) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var products by remember { mutableStateOf(emptyList<ProductEntity>()) }
    var movements by remember { mutableStateOf(emptyList<InventoryMovementEntity>()) }
    var adjustment by remember { mutableStateOf<ProductEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            products = when (filter) {
                "LOW" -> AppGraph.products.lowStock()
                "OUT" -> AppGraph.products.outOfStock()
                else -> AppGraph.products.page(query, null, false, "stock_asc", 0, 100).first.filter { !it.trackInventory || true }
            }.filter { query.isBlank() || it.name.contains(query, true) || it.sku.contains(query, true) || it.barcode.contains(query, true) }
            movements = AppGraph.db.inventoryDao().recent(20, 0)
        }
    }
    LaunchedEffect(filter, query) { refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("Inventory", fontWeight = FontWeight.Bold) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notice?.let { InventoryNotice(it) { notice = null } }
            OutlinedTextField(query, { query = it }, label = { Text("Cari produk / SKU / barcode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "Semua stok", "LOW" to "Stok rendah", "OUT" to "Stok habis").forEach { (id, label) ->
                    FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                }
            }
            Text("Stok saat ini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (products.isEmpty()) Text("Tidak ada produk sesuai filter.", Modifier.padding(12.dp)) else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(products, key = { it.id }) { product ->
                    val state = when {
                        !product.trackInventory -> "Tidak dilacak"
                        product.stockQty <= 0 -> "HABIS"
                        product.stockQty <= product.lowStockAlert -> "RENDAH"
                        else -> "Aman"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.SemiBold)
                                Text("${product.sku.ifBlank { product.barcode.ifBlank { "Tanpa SKU" } }} • Min ${product.lowStockAlert} ${product.uom}", style = MaterialTheme.typography.bodySmall)
                                Text("${product.stockQty} ${product.uom} • $state", color = if (state == "HABIS") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                            if (canAdjust) TextButton(onClick = { adjustment = product }) { Text("ADJUST") }
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Pergerakan stok terbaru", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(movements, key = { it.id }) { m ->
                    val productName = products.firstOrNull { it.id == m.productId }?.name ?: "Produk #${m.productId}"
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) { Text("$productName • ${m.type}"); Text("${Dates.dmyhm(m.createdAt)}${if (m.note.isBlank()) "" else " • ${m.note}"}", style = MaterialTheme.typography.bodySmall) }
                        Text(if (m.quantity >= 0) "+${m.quantity}" else m.quantity.toString(), color = if (m.quantity >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    adjustment?.let { product -> InventoryAdjustmentDialog(product, userId, onDismiss = { adjustment = null }, onResult = { message -> adjustment = null; notice = message; refresh() }) }
}

@Composable
private fun InventoryNotice(message: String, dismiss: () -> Unit) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f))
        TextButton(onClick = dismiss) { Text("Tutup") }
    }
}

@Composable
private fun InventoryAdjustmentDialog(product: ProductEntity, userId: Long, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("ADD") }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjustment: ${product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stok saat ini: ${product.stockQty} ${product.uom}")
                listOf("ADD" to "Tambah", "REMOVE" to "Kurangi", "SET" to "Set stok").forEach { (id, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == id, onClick = { mode = id })
                        Text(title)
                    }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text(if (mode == "SET") "Stok baru" else "Jumlah") }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("Alasan wajib") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val quantity = Money.parse(amount)
                if (quantity <= 0 || reason.trim().isEmpty()) error = "Jumlah dan alasan wajib diisi"
                else scope.launch {
                    if (AppGraph.products.adjustStock(product, mode, quantity, reason.trim(), userId)) onResult("Adjustment stok tersimpan")
                    else error = "Adjustment gagal; stok tidak mencukupi atau data invalid"
                }
            }) { Text("SIMPAN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}
