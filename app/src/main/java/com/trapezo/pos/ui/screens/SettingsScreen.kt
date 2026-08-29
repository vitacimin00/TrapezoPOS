package com.trapezo.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.trapezo.pos.AppGraph
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.entity.StoreEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.FormSection
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.ScreenHeader
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

private enum class SettingsSection(val label: String, val description: String, val icon: ImageVector) {
    STORE("Toko", "Identitas, kontak, dan logo", Icons.Default.Store),
    POS("Kasir", "Pajak, service charge, pembulatan, invoice", Icons.Default.Tune),
    RECEIPT("Struk", "Ukuran kertas dan konten struk", Icons.Default.Receipt),
    PRINTER("Printer", "Printer thermal Bluetooth", Icons.Default.Print),
    DATA("Data", "Backup dan restore database", Icons.Default.Storage),
    USERS("Pengguna", "Akun kasir dan administrator", Icons.Default.Group)
}

/**
 * Settings uses grouped navigation instead of one endless scroll: a category list plus a
 * detail panel on tablet, and a list → subpage flow on phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: UserEntity,
    onSessionRefresh: () -> Unit,
    onSessionReauth: (String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    val widthClass = currentWidthClass
    val isAdmin = user.role == "ADMIN"
    val backup = remember { BackupService(context) }
    val printers = remember { BluetoothPrinterService(context) }

    var draft by remember { mutableStateOf(SettingsDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var persistedLogo by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var paired by remember { mutableStateOf(emptyList<BluetoothPrinterService.PairedPrinter>()) }
    var selectPrinter by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var usersOpen by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val store = AppGraph.store.get()
            persistedLogo = store.logo
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
    LaunchedEffect(widthClass) {
        if (widthClass.isExpanded && section == null) section = SettingsSection.STORE
    }

    val backupLauncher = rememberLauncherForActivityResult(
        // Generic MIME so the SAF picker preserves the ".trpz" extension instead of
        // force-appending ".zip"; the package content is self-describing via its magic.
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = backup.backupTo(uri)
            if (result.ok) feedback?.success("Backup berhasil dibuat") else feedback?.error(result.message)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }
    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = StoreLogoStorage.importFromUri(context, uri)
            if (path != null) {
                // Replacing an unsaved draft logo: delete the now-unreferenced previous draft.
                val previousDraft = draft.store.logo
                if (previousDraft != null && previousDraft != persistedLogo) {
                    StoreLogoStorage.deleteManaged(previousDraft)
                }
                draft = draft.copy(store = draft.store.copy(logo = path))
                feedback?.info("Logo dipilih. Tekan Simpan untuk menerapkannya.")
            } else feedback?.error("Logo tidak dapat dibaca")
        }
    }

    // If the Settings screen is left (or the app closes) with an unsaved draft logo,
    // retire that unreferenced managed file. The persisted logo is never touched here.
    val latestDraftLogo by rememberUpdatedState(draft.store.logo)
    val latestPersistedLogo by rememberUpdatedState(persistedLogo)
    DisposableEffect(Unit) {
        onDispose {
            val draftLogo = latestDraftLogo
            if (draftLogo != null && draftLogo != latestPersistedLogo) {
                StoreLogoStorage.deleteManaged(draftLogo)
            }
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            when (val result = printers.pairedPrinters()) {
                is BluetoothPrinterService.Result.Success -> { paired = result.value; selectPrinter = true }
                is BluetoothPrinterService.Result.Error -> feedback?.error(result.message)
            }
        } else feedback?.error("Izin Bluetooth diperlukan untuk memilih printer")
    }
    fun openPrinterPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            when (val result = printers.pairedPrinters()) {
                is BluetoothPrinterService.Result.Success -> { paired = result.value; selectPrinter = true }
                is BluetoothPrinterService.Result.Error -> feedback?.error(result.message)
            }
        }
    }

    if (!isAdmin) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Pengaturan")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EmptyState(
                title = "Akses administrator diperlukan",
                message = "Akun kasir dapat membuka shift, bertransaksi, melihat riwayat, dan mencatat kas. " +
                    "Pengaturan toko, printer, data, dan pengguna hanya untuk administrator.",
                icon = Icons.Default.Tune
            )
        }
        return
    }
    if (!loaded) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Pengaturan")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LoadingState("Memuat pengaturan…")
        }
        return
    }

    val detail: @Composable (SettingsSection) -> Unit = { current ->
        when (current) {
            SettingsSection.STORE -> StoreSettings(
                draft = draft,
                onDraft = { draft = it },
                onPickLogo = { logoLauncher.launch("image/*") },
                onSave = {
                    scope.launch {
                        try {
                            AppGraph.store.save(draft.store, user.id)
                            // Success: the new logo is now authoritative; retire the old one.
                            val old = persistedLogo
                            if (old != null && old != draft.store.logo) {
                                StoreLogoStorage.deleteManaged(old)
                            }
                            persistedLogo = draft.store.logo
                            feedback?.success("Pengaturan toko disimpan")
                        } catch (e: Exception) {
                            // Keep both the new draft (for retry) and the old persisted logo.
                            feedback?.error(e.message ?: "Gagal menyimpan pengaturan toko")
                        }
                    }
                }
            )
            SettingsSection.POS -> PosSettings(
                draft = draft,
                onDraft = { draft = it },
                onSave = {
                    val tax = Money.parseOrNull(draft.taxPercent)
                    val service = Money.parseOrNull(draft.servicePercent)
                    val rounding = Money.parseOrNull(draft.rounding)
                    when {
                        tax == null || service == null || rounding == null ->
                            feedback?.error("Nilai pajak, service charge, atau pembulatan tidak valid")
                        tax > 100 || service > 100 ->
                            feedback?.error("Pajak dan service charge maksimal 100%")
                        else -> scope.launch {
                            val err = AppGraph.settings.savePosConfiguration(
                                draft.invoicePrefix, tax, service, rounding, user.id
                            )
                            if (err == null) feedback?.success("Pengaturan kasir disimpan")
                            else feedback?.error(err)
                        }
                    }
                }
            )
            SettingsSection.RECEIPT -> ReceiptSettings(
                draft = draft,
                onDraft = { draft = it },
                onSave = {
                    scope.launch {
                        val err = AppGraph.settings.saveReceiptConfiguration(
                            draft.receiptPaper, draft.receiptFooter,
                            draft.showLogo, draft.showAddress, draft.showPhone, user.id
                        )
                        if (err == null) feedback?.success("Pengaturan struk disimpan")
                        else feedback?.error(err)
                    }
                }
            )
            SettingsSection.PRINTER -> PrinterSettings(
                address = draft.printerAddress,
                onPick = ::openPrinterPicker,
                onTest = {
                    scope.launch {
                        when (val result = printers.testPrint(draft.printerAddress)) {
                            is BluetoothPrinterService.Result.Success -> feedback?.success("Test print terkirim")
                            is BluetoothPrinterService.Result.Error ->
                                feedback?.error("${result.message}. Gunakan Bagikan PDF sebagai alternatif.")
                        }
                    }
                }
            )
            SettingsSection.DATA -> DataSettings(
                onBackup = { backupLauncher.launch(backup.suggestedName()) },
                onRestore = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-sqlite3", "*/*")) }
            )
            SettingsSection.USERS -> UserSettings(onManage = { usersOpen = true })
        }
    }

    if (widthClass.isExpanded) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Pengaturan", subtitle = "Konfigurasi toko dan perangkat")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    SettingsSection.entries.forEach { item ->
                        SettingsCategoryRow(
                            section = item,
                            selected = section == item,
                            onClick = { section = item }
                        )
                    }
                }
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    section?.let { detail(it) } ?: EmptyState(
                        title = "Pilih kategori",
                        message = "Pilih kategori pengaturan di sebelah kiri.",
                        icon = Icons.Default.Tune
                    )
                }
            }
        }
    } else {
        val current = section
        if (current == null) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(title = "Pengaturan", subtitle = "Konfigurasi toko dan perangkat")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingsSection.entries.forEach { item ->
                        SettingsCategoryRow(section = item, selected = false, onClick = { section = item })
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(horizontal = Space.sm, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { section = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali ke daftar pengaturan")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(current.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            current.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) { detail(current) }
            }
        }
    }

    if (selectPrinter) {
        PrinterPickerSheet(
            printers = paired,
            onDismiss = { selectPrinter = false },
            onSelect = { selected ->
                // Persist first; only on success update the draft, close the picker, and
                // claim the printer. On failure the old address stays untouched.
                scope.launch {
                    try {
                        AppGraph.settings.putSetting("printer.address", selected.address, user.id)
                        draft = draft.copy(printerAddress = selected.address)
                        selectPrinter = false
                        feedback?.success("Printer ${selected.name} dipilih")
                    } catch (e: Exception) {
                        feedback?.error(e.message ?: "Gagal menyimpan printer")
                    }
                }
            }
        )
    }
    restoreUri?.let { uri ->
        ConfirmActionDialog(
            title = "Ganti data dengan file backup?",
            message = "Seluruh data saat ini akan digantikan setelah file backup diverifikasi. " +
                "Anda diminta masuk kembali menggunakan akun dari backup setelah proses selesai.",
            confirmLabel = "Restore",
            tone = Tone.DANGER,
            onDismiss = { restoreUri = null },
            onConfirm = {
                restoreUri = null
                scope.launch {
                    val result = backup.restoreFrom(uri)
                    if (result.ok) {
                        // A restored DB may hold different users/roles/credentials; force
                        // re-authentication instead of keeping the stale in-memory session.
                        onSessionReauth(result.message)
                    } else feedback?.error(result.message)
                }
            }
        )
    }
    if (usersOpen) {
        UsersSheet(
            actor = user,
            onDismiss = { usersOpen = false },
            onMessage = { ok, message ->
                if (ok) feedback?.success(message) else feedback?.error(message)
            },
            onSelfUpdated = onSessionRefresh
        )
    }
}

@Composable
private fun SettingsCategoryRow(section: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Icon(
                section.icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    section.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsPane(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg)
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.lg)
    ) { content() }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    numeric: Boolean = false,
    supporting: String? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = Radius.field,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Touch.min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun StoreSettings(
    draft: SettingsDraft,
    onDraft: (SettingsDraft) -> Unit,
    onPickLogo: () -> Unit,
    onSave: () -> Unit
) {
    SettingsPane {
        FormSection("Identitas toko") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SettingsTextField("Nama toko", draft.store.name) { onDraft(draft.copy(store = draft.store.copy(name = it))) }
                SettingsTextField("Alamat", draft.store.address) { onDraft(draft.copy(store = draft.store.copy(address = it))) }
                SettingsTextField("Nomor telepon", draft.store.phone) { onDraft(draft.copy(store = draft.store.copy(phone = it))) }
                SettingsTextField("Email", draft.store.email) { onDraft(draft.copy(store = draft.store.copy(email = it))) }
            }
        }
        FormSection("Logo") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedButton(
                    onClick = onPickLogo,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) { Text(if (draft.store.logo.isNullOrBlank()) "Pilih Logo Toko" else "Ganti Logo Toko") }
                if (!draft.store.logo.isNullOrBlank()) {
                    StatusBadge("Logo tersimpan", Tone.SUCCESS)
                }
            }
        }
        Button(
            onClick = onSave,
            shape = Radius.field,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
        ) { Text("Simpan Pengaturan Toko", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PosSettings(draft: SettingsDraft, onDraft: (SettingsDraft) -> Unit, onSave: () -> Unit) {
    SettingsPane {
        FormSection("Nomor invoice") {
            SettingsTextField(
                "Prefix invoice",
                draft.invoicePrefix,
                supporting = "Contoh: INV menghasilkan INV-20260829-0001"
            ) { onDraft(draft.copy(invoicePrefix = it)) }
        }
        FormSection("Pajak dan biaya") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SettingsTextField("Pajak (%)", draft.taxPercent, numeric = true) { onDraft(draft.copy(taxPercent = it)) }
                SettingsTextField("Service charge (%)", draft.servicePercent, numeric = true) { onDraft(draft.copy(servicePercent = it)) }
                SettingsTextField(
                    "Pembulatan",
                    draft.rounding,
                    numeric = true,
                    supporting = "0 = tanpa pembulatan. Contoh lain: 100, 500, 1000"
                ) { onDraft(draft.copy(rounding = it)) }
            }
        }
        Button(
            onClick = onSave,
            shape = Radius.field,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
        ) { Text("Simpan Pengaturan Kasir", fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptSettings(draft: SettingsDraft, onDraft: (SettingsDraft) -> Unit, onSave: () -> Unit) {
    SettingsPane {
        FormSection("Ukuran kertas") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf("58" to "58 mm", "80" to "80 mm").forEach { (value, label) ->
                    FilterChip(
                        selected = draft.receiptPaper == value,
                        onClick = { onDraft(draft.copy(receiptPaper = value)) },
                        label = { Text(label) },
                        shape = Radius.control,
                        modifier = Modifier.heightIn(min = Touch.min)
                    )
                }
            }
        }
        FormSection("Konten struk") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SettingsToggle("Tampilkan logo", draft.showLogo) { onDraft(draft.copy(showLogo = it)) }
                SettingsToggle("Tampilkan alamat", draft.showAddress) { onDraft(draft.copy(showAddress = it)) }
                SettingsToggle("Tampilkan telepon", draft.showPhone) { onDraft(draft.copy(showPhone = it)) }
                OutlinedTextField(
                    draft.receiptFooter,
                    { onDraft(draft.copy(receiptFooter = it)) },
                    label = { Text("Footer struk") },
                    shape = Radius.field,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Button(
            onClick = onSave,
            shape = Radius.field,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
        ) { Text("Simpan Pengaturan Struk", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PrinterSettings(address: String, onPick: () -> Unit, onTest: () -> Unit) {
    SettingsPane {
        FormSection("Printer thermal") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (address.isBlank()) {
                    StatusBadge("Belum ada printer dipilih", Tone.WARNING)
                } else {
                    StatusBadge("Printer terpilih", Tone.SUCCESS)
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onPick,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Pilih Printer Bluetooth")
                }
                Button(
                    onClick = onTest,
                    enabled = address.isNotBlank(),
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) { Text("Test Print") }
                Text(
                    "Bila printer tidak tersambung, struk tetap dapat dibagikan sebagai PDF dari halaman Transaksi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DataSettings(onBackup: () -> Unit, onRestore: () -> Unit) {
    SettingsPane {
        FormSection("Backup") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    "Simpan salinan seluruh data Trapezo POS ke penyimpanan perangkat atau layanan cloud pilihan Anda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onBackup,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) { Text("Buat Backup Sekarang") }
            }
        }
        Surface(
            color = TrapezoStatus.dangerContainer,
            shape = Radius.card,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Restore")
                Text(
                    "Restore menggantikan seluruh data saat ini dengan isi file backup. " +
                        "File akan diperiksa terlebih dahulu, dan data lama disimpan sebagai cadangan pemulihan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TrapezoStatus.danger
                )
                OutlinedButton(
                    onClick = onRestore,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) { Text("Pilih File Backup") }
            }
        }
    }
}

@Composable
private fun UserSettings(onManage: () -> Unit) {
    SettingsPane {
        FormSection("Akun pengguna") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    "Kelola akun kasir dan administrator. Minimal satu administrator aktif selalu dipertahankan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onManage,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                ) { Text("Kelola Pengguna") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrinterPickerSheet(
    printers: List<BluetoothPrinterService.PairedPrinter>,
    onDismiss: () -> Unit,
    onSelect: (BluetoothPrinterService.PairedPrinter) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Printer Terpasang", style = MaterialTheme.typography.titleMedium)
            if (printers.isEmpty()) {
                Text(
                    "Tidak ada printer Bluetooth yang dipasangkan. Pasangkan printer melalui Pengaturan Android terlebih dahulu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                printers.forEach { printer ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Touch.control)
                            .clickable { onSelect(printer) }
                            .padding(vertical = Space.sm)
                    ) {
                        Text(printer.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            printer.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsersSheet(
    actor: UserEntity,
    onDismiss: () -> Unit,
    onMessage: (Boolean, String) -> Unit,
    onSelfUpdated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf(emptyList<UserEntity>()) }
    var editorTarget by remember { mutableStateOf<UserEntity?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            users = AppGraph.users.all()
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxHeight(0.9f)
                .padding(horizontal = Space.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kelola Pengguna", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(
                    onClick = { editorTarget = null; editorOpen = true },
                    shape = Radius.field,
                    modifier = Modifier.heightIn(min = Touch.control)
                ) { Text("Tambah") }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) { LoadingState("Memuat pengguna…") }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    items(users, key = { it.id }) { target ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = Radius.card,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(Space.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        target.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${target.username} • ${Labels.role(target.role)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StatusBadge(
                                    if (target.isActive) "Aktif" else "Nonaktif",
                                    if (target.isActive) Tone.SUCCESS else Tone.NEUTRAL
                                )
                                IconButton(
                                    onClick = { editorTarget = target; editorOpen = true },
                                    modifier = Modifier.size(Touch.control)
                                ) { Icon(Icons.Default.Edit, contentDescription = "Edit ${target.name}") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        UserEditorSheet(
            existing = editorTarget,
            actor = actor,
            onDismiss = { editorOpen = false; editorTarget = null },
            onSaved = { message ->
                val wasSelfEdit = editorTarget?.id == actor.id
                editorOpen = false
                editorTarget = null
                onMessage(true, message)
                refresh()
                // If the logged-in admin edited their OWN record, refresh the session so
                // role/identity follow the authoritative DB immediately.
                if (wasSelfEdit) onSelfUpdated()
            },
            onError = { onMessage(false, it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserEditorSheet(
    existing: UserEntity?,
    actor: UserEntity,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: "CASHIER") }
    var password by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(existing?.isActive ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
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
                if (existing == null) "Tambah Pengguna" else "Edit Pengguna",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                username, { username = it },
                label = { Text("Username") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text("Nama lengkap") },
                singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf("ADMIN", "CASHIER").forEach { code ->
                    FilterChip(
                        selected = role == code,
                        onClick = { role = code },
                        label = { Text(Labels.role(code)) },
                        shape = Radius.control,
                        modifier = Modifier.heightIn(min = Touch.min)
                    )
                }
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = {
                    Text(if (existing == null) "Password (min. 8 karakter)" else "Password baru (kosongkan bila tidak diubah)")
                },
                singleLine = true,
                shape = Radius.field,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            SettingsToggle("Akun aktif", active) { active = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text("Batal") }
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            val result = AppGraph.users.save(
                                existing, username, name, role,
                                password.takeIf { it.isNotBlank() }, active, actor.id
                            )
                            saving = false
                            if (result.error != null) {
                                error = result.error
                                onError(result.error)
                            } else onSaved("Pengguna ${result.user!!.username} disimpan")
                        }
                    },
                    enabled = !saving,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text(if (saving) "Menyimpan…" else "Simpan", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
