package com.trapezo.pos.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trapezo.pos.AppGraph
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.entity.StoreEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.utils.Money
import com.trapezo.pos.utils.StoreLogoStorage
import kotlinx.coroutines.launch

private data class SettingsDraft(
    val store: StoreEntity = StoreEntity(name = "Toko Saya"),
    val invoicePrefix: String = "INV",
    val taxPercent: String = "0",
    val servicePercent: String = "0",
    val rounding: String = "0",
    val receiptPaper: String = "80",
    val receiptFooter: String = "Terima kasih telah berbelanja!",
    val showAddress: Boolean = true,
    val showPhone: Boolean = true,
    val showLogo: Boolean = true,
    val printerAddress: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(user: UserEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAdmin = user.role == "ADMIN"
    val backup = remember { BackupService(context) }
    val printers = remember { BluetoothPrinterService(context) }
    var draft by remember { mutableStateOf(SettingsDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf(emptyList<BluetoothPrinterService.PairedPrinter>()) }
    var selectPrinter by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var usersOpen by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val store = AppGraph.store.get()
            draft = SettingsDraft(
                store = store,
                invoicePrefix = AppGraph.settings.raw("pos.invoice_prefix", "INV"),
                taxPercent = AppGraph.settings.raw("pos.tax_percent", "0"),
                servicePercent = AppGraph.settings.raw("pos.service_percent", "0"),
                rounding = AppGraph.settings.raw("pos.rounding", "0"),
                receiptPaper = AppGraph.settings.raw("receipt.paper", "80mm").filter { it.isDigit() }.ifBlank { "80" },
                receiptFooter = AppGraph.settings.raw("receipt.footer", "Terima kasih telah berbelanja!"),
                showAddress = AppGraph.settings.bool("receipt.show_address", true),
                showPhone = AppGraph.settings.bool("receipt.show_phone", true),
                showLogo = AppGraph.settings.bool("receipt.show_logo", true),
                printerAddress = AppGraph.settings.raw("printer.address", "")
            )
            loaded = true
        }
    }
    LaunchedEffect(Unit) { load() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = backup.backupTo(uri)
            notice = result.message
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            restoreConfirm = true
        }
    }
    val logoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val path = StoreLogoStorage.importFromUri(context, uri)
            if (path != null) {
                draft = draft.copy(store = draft.store.copy(logo = path))
                notice = "Logo toko dipilih. Tekan Simpan Toko untuk menerapkannya."
            } else notice = "Logo tidak dapat dibaca"
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            when (val result = printers.pairedPrinters()) {
                is BluetoothPrinterService.Result.Success -> { paired = result.value; selectPrinter = true }
                is BluetoothPrinterService.Result.Error -> notice = result.message
            }
        } else notice = "Izin Bluetooth diperlukan untuk memilih atau mengetes printer"
    }

    fun openPrinterPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            when (val result = printers.pairedPrinters()) {
                is BluetoothPrinterService.Result.Success -> { paired = result.value; selectPrinter = true }
                is BluetoothPrinterService.Result.Error -> notice = result.message
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }) }) { padding ->
        if (!loaded) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Memuat pengaturan…") }
        } else if (!isAdmin) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pengaturan Admin", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Akun CASHIER dapat membuka shift, melakukan transaksi, melihat transaksi, dan cash in/out. Pengaturan toko, printer, backup, dan user hanya untuk ADMIN.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (notice != null) item { SettingsNotice(notice!!) { notice = null } }
                item { SectionHeading("Toko") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(draft.store.name, { draft = draft.copy(store = draft.store.copy(name = it)) }, label = { Text("Nama toko") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.store.address, { draft = draft.copy(store = draft.store.copy(address = it)) }, label = { Text("Alamat") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.store.phone, { draft = draft.copy(store = draft.store.copy(phone = it)) }, label = { Text("Nomor telepon") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.store.email, { draft = draft.copy(store = draft.store.copy(email = it)) }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { logoLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (draft.store.logo.isNullOrBlank()) "PILIH LOGO TOKO" else "GANTI LOGO TOKO")
                            }
                            draft.store.logo?.let { Text("Logo tersimpan: ${it.substringAfterLast('\\')}", style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = {
                                scope.launch {
                                    AppGraph.store.save(draft.store, user.id)
                                    notice = "Pengaturan toko disimpan"
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("SIMPAN TOKO") }
                        }
                    }
                }
                item { SectionHeading("POS") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(draft.invoicePrefix, { draft = draft.copy(invoicePrefix = it) }, label = { Text("Prefix invoice") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.taxPercent, { draft = draft.copy(taxPercent = it) }, label = { Text("Pajak (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.servicePercent, { draft = draft.copy(servicePercent = it) }, label = { Text("Service charge (%)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(draft.rounding, { draft = draft.copy(rounding = it) }, label = { Text("Pembulatan (0 / 100 / 500 / 1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                val tax = Money.parseOrNull(draft.taxPercent)
                                val service = Money.parseOrNull(draft.servicePercent)
                                val rounding = Money.parseOrNull(draft.rounding)
                                if (tax == null || service == null || rounding == null) notice = "Nilai pajak/service/pembulatan tidak valid"
                                else if (tax > 100 || service > 100) notice = "Pajak dan service charge maksimal 100%"
                                else {
                                    scope.launch {
                                        AppGraph.settings.putSetting("pos.invoice_prefix", draft.invoicePrefix.trim().ifBlank { "INV" }, user.id)
                                        AppGraph.settings.putLongSetting("pos.tax_percent", tax, user.id)
                                        AppGraph.settings.putLongSetting("pos.service_percent", service, user.id)
                                        AppGraph.settings.putLongSetting("pos.rounding", rounding, user.id)
                                        notice = "Pengaturan POS disimpan"
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("SIMPAN POS") }
                        }
                    }
                }
                item { SectionHeading("Struk") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("58" to "58 mm", "80" to "80 mm").forEach { (value, label) ->
                                    FilterChip(selected = draft.receiptPaper == value, onClick = { draft = draft.copy(receiptPaper = value) }, label = { Text(label) })
                                }
                            }
                            ToggleSetting("Tampilkan logo", draft.showLogo) { draft = draft.copy(showLogo = it) }
                            ToggleSetting("Tampilkan alamat", draft.showAddress) { draft = draft.copy(showAddress = it) }
                            ToggleSetting("Tampilkan telepon", draft.showPhone) { draft = draft.copy(showPhone = it) }
                            OutlinedTextField(draft.receiptFooter, { draft = draft.copy(receiptFooter = it) }, label = { Text("Footer struk") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                scope.launch {
                                    AppGraph.settings.putSetting("receipt.paper", draft.receiptPaper + "mm", user.id)
                                    AppGraph.settings.putSetting("receipt.footer", draft.receiptFooter, user.id)
                                    AppGraph.settings.putSetting("receipt.show_logo", if (draft.showLogo) "1" else "0", user.id)
                                    AppGraph.settings.putSetting("receipt.show_address", if (draft.showAddress) "1" else "0", user.id)
                                    AppGraph.settings.putSetting("receipt.show_phone", if (draft.showPhone) "1" else "0", user.id)
                                    notice = "Pengaturan struk disimpan"
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("SIMPAN STRUK") }
                        }
                    }
                }
                item { SectionHeading("Printer thermal") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (draft.printerAddress.isBlank()) "Belum ada printer dipilih" else "Printer: ${draft.printerAddress}")
                            OutlinedButton(onClick = ::openPrinterPicker, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Bluetooth, null); Text(" PILIH PRINTER BLUETOOTH") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        when (val result = printers.testPrint(draft.printerAddress)) {
                                            is BluetoothPrinterService.Result.Success -> notice = "Test print berhasil dikirim ke printer"
                                            is BluetoothPrinterService.Result.Error -> notice = "${result.message}. Gunakan Bagikan PDF sebagai fallback."
                                        }
                                    }
                                },
                                enabled = draft.printerAddress.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("PRINT TEST") }
                            Text("Bila printer tidak tersambung, halaman transaksi menyediakan Bagikan PDF struk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionHeading("Database") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { backupLauncher.launch(backup.suggestedName()) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Backup, null); Text(" BACKUP DATABASE") }
                            OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("RESTORE DATABASE") }
                            Text("Restore memvalidasi file, menyimpan database lama terlebih dahulu, lalu meminta aplikasi dibuka kembali.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { SectionHeading("Pengguna") }
                item { OutlinedButton(onClick = { usersOpen = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Text(" KELOLA USER") } }
            }
        }
    }

    if (selectPrinter) PrinterPickerDialog(paired, onDismiss = { selectPrinter = false }, onSelect = { selected ->
        draft = draft.copy(printerAddress = selected.address)
        selectPrinter = false
        scope.launch { AppGraph.settings.putSetting("printer.address", selected.address, user.id); notice = "Printer ${selected.name} dipilih" }
    })
    if (restoreConfirm && restoreUri != null) RestoreConfirmDialog(onDismiss = { restoreConfirm = false; restoreUri = null }, onConfirm = {
        val uri = restoreUri ?: return@RestoreConfirmDialog
        restoreConfirm = false
        scope.launch {
            val result = backup.restoreFrom(uri)
            notice = result.message
            restoreUri = null
        }
    })
    if (usersOpen) UsersDialog(actor = user, onDismiss = { usersOpen = false }, onMessage = { notice = it })
}

@Composable
private fun SettingsNotice(message: String, dismiss: () -> Unit) = Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f))
        TextButton(onClick = dismiss) { Text("Tutup") }
    }
}

@Composable
private fun SectionHeading(label: String) = Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun ToggleSetting(label: String, value: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = set)
    }

@Composable
private fun PrinterPickerDialog(
    printers: List<BluetoothPrinterService.PairedPrinter>,
    onDismiss: () -> Unit,
    onSelect: (BluetoothPrinterService.PairedPrinter) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih printer yang dipasangkan") },
        text = {
            if (printers.isEmpty()) {
                Text("Tidak ada printer Bluetooth yang dipasangkan. Pasangkan printer dari Pengaturan Android terlebih dahulu.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    printers.forEach { printer ->
                        TextButton(onClick = { onSelect(printer) }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(printer.name)
                                Text(printer.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
private fun RestoreConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore database?") },
        text = { Text("Data saat ini akan digantikan oleh file backup setelah file tersebut tervalidasi. Database lama akan disimpan sebagai cadangan pemulihan bila penggantian gagal. Lanjutkan?") },
        confirmButton = { Button(onClick = onConfirm) { Text("RESTORE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}

@Composable
private fun UsersDialog(actor: UserEntity, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf(emptyList<UserEntity>()) }
    var edit by remember { mutableStateOf<UserEntity?>(null) }
    var create by remember { mutableStateOf(false) }
    fun refresh() { scope.launch { users = AppGraph.users.all() } }
    LaunchedEffect(Unit) { refresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kelola user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { create = true }, modifier = Modifier.fillMaxWidth()) { Text("+ TAMBAH USER") }
                LazyColumn(Modifier.height(230.dp)) {
                    items(users, key = { it.id }) { target ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(target.name, fontWeight = FontWeight.SemiBold)
                                Text("${target.username} • ${target.role} • ${if (target.isActive) "Aktif" else "Nonaktif"}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { edit = target }) { Icon(Icons.Default.Edit, "Edit user") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
    if (create) UserEditorDialog(
        existing = null,
        actor = actor,
        onDismiss = { create = false },
        onSaved = { create = false; onMessage(it); refresh() }
    )
    edit?.let { target ->
        UserEditorDialog(
            existing = target,
            actor = actor,
            onDismiss = { edit = null },
            onSaved = { edit = null; onMessage(it); refresh() }
        )
    }
}

@Composable
private fun UserEditorDialog(existing: UserEntity?, actor: UserEntity, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: "CASHIER") }
    var password by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(existing?.isActive ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Tambah user" else "Edit user") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Nama") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ADMIN" to "Admin", "CASHIER" to "Cashier").forEach { (id, label) ->
                        FilterChip(selected = role == id, onClick = { role = id }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (existing == null) "Password (min. 8)" else "Password baru (min. 8; kosong = tidak berubah)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                ToggleSetting("Akun aktif", active) { active = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val result = AppGraph.users.save(
                        existing,
                        username,
                        name,
                        role,
                        password.takeIf { it.isNotBlank() },
                        active,
                        actor.id
                    )
                    if (result.error != null) error = result.error else onSaved("User ${result.user!!.username} disimpan")
                }
            }) { Text("SIMPAN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } }
    )
}
