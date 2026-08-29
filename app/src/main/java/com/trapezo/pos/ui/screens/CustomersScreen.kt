package com.trapezo.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.trapezo.pos.data.entity.CustomerEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MoneyText
import com.trapezo.pos.ui.components.ScreenHeader
import com.trapezo.pos.ui.components.SearchField
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.rememberFeedback
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(user: UserEntity) {
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    val canManage = user.role == "ADMIN"
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var customers by remember { mutableStateOf(emptyList<CustomerEntity>()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var editorTarget by remember { mutableStateOf<CustomerEntity?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CustomerEntity?>(null) }

    fun reloadFirstPage() { page = 0; requestVersion++ }

    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
        page = 0
    }
    LaunchedEffect(debouncedQuery, page, requestVersion) {
        val version = ++requestVersion
        if (page == 0) loading = true
        val result = AppGraph.customers.page(debouncedQuery, page)
        if (version != requestVersion) return@LaunchedEffect
        customers = if (page == 0) result.first else (customers + result.first).distinctBy { it.id }
        total = result.second
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Customer",
            subtitle = "$total customer terdaftar",
            actions = {
                if (canManage) {
                    Button(
                        onClick = { editorTarget = null; editorOpen = true },
                        shape = Radius.field,
                        modifier = Modifier.heightIn(min = Touch.control)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text("Tambah")
                    }
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            SearchField(query, { query = it }, "Cari nama, nomor HP, atau kode")
        }

        when {
            loading -> LoadingState("Memuat data customer…")
            customers.isEmpty() && debouncedQuery.isNotBlank() -> EmptyState(
                title = "Tidak ada hasil",
                message = "Tidak ada customer cocok untuk \"$debouncedQuery\".",
                icon = Icons.Default.People
            )
            customers.isEmpty() -> EmptyState(
                title = "Belum ada customer",
                message = "Tambahkan customer untuk mencatat pembelian atas nama pelanggan tertentu.",
                icon = Icons.Default.People,
                actionLabel = if (canManage) "Tambah Customer" else null,
                onAction = if (canManage) ({ editorTarget = null; editorOpen = true }) else null
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.lg, end = Space.lg, bottom = Space.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(customers, key = { it.id }) { customer ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = Radius.card,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canManage) { editorTarget = customer; editorOpen = true }
                    ) {
                        Row(
                            Modifier.padding(Space.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.md)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    customer.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOf(customer.code, customer.phone).filter { it.isNotBlank() }
                                        .joinToString(" • ").ifBlank { "Tanpa kontak" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${customer.points} poin",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                MoneyText(customer.balance, weight = FontWeight.Medium)
                            }
                            if (canManage) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Edit ${customer.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (customers.size < total) {
                    item {
                        TextButton(onClick = { page++ }, modifier = Modifier.fillMaxWidth()) {
                            Text("Muat lebih banyak")
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        CustomerEditorSheet(
            existing = editorTarget,
            onDismiss = { editorOpen = false; editorTarget = null },
            onSave = { entity ->
                val result = AppGraph.customers.save(entity, user.id)
                if (result.error == null) {
                    feedback?.success(if (editorTarget == null) "Customer tersimpan" else "Customer diperbarui")
                    reloadFirstPage()
                } else feedback?.error(result.error)
                result.error == null
            },
            onRequestDelete = editorTarget?.let { target -> { deleteTarget = target } }
        )
    }

    deleteTarget?.let { target ->
        ConfirmActionDialog(
            title = "Hapus customer?",
            message = "\"${target.name}\" akan dihapus dari daftar customer. Riwayat transaksi tetap tersimpan.",
            confirmLabel = "Hapus",
            tone = Tone.DANGER,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val error = AppGraph.customers.delete(target, user.id)
                    if (error == null) {
                        editorOpen = false
                        editorTarget = null
                        feedback?.success("Customer dihapus")
                        reloadFirstPage()
                    } else feedback?.error(error)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerEditorSheet(
    existing: CustomerEntity?,
    onDismiss: () -> Unit,
    onSave: suspend (CustomerEntity) -> Boolean,
    onRequestDelete: (() -> Unit)?
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(existing?.code.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var email by remember { mutableStateOf(existing?.email.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(
                if (existing == null) "Tambah Customer" else "Edit Customer",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                code, { code = it },
                label = { Text("Kode (kosong = otomatis)") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text("Nama *") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                phone, { phone = it },
                label = { Text("Nomor HP") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                email, { email = it },
                label = { Text("Email") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                address, { address = it },
                label = { Text("Alamat") },
                minLines = 2, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Poin dan saldo tidak dapat diubah langsung dari profil customer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (onRequestDelete != null) {
                    OutlinedButton(
                        onClick = onRequestDelete,
                        shape = Radius.field,
                        modifier = Modifier.heightIn(min = Touch.control)
                    ) { Text("Hapus") }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text("Batal") }
                Button(
                    onClick = {
                        if (saving) return@Button
                        scope.launch {
                            saving = true
                            val ok = try {
                                onSave(
                                    CustomerEntity(
                                        id = existing?.id ?: 0,
                                        code = code,
                                        name = name,
                                        phone = phone,
                                        email = email,
                                        address = address,
                                        points = existing?.points ?: 0,
                                        balance = existing?.balance ?: 0,
                                        createdAt = existing?.createdAt ?: System.currentTimeMillis()
                                    )
                                )
                            } finally { saving = false }
                            if (ok) onDismiss()
                        }
                    },
                    enabled = name.isNotBlank() && !saving,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text(if (saving) "Menyimpan…" else "Simpan", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
