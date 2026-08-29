package com.trapezo.pos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trapezo.pos.backup.BackupService
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.ProvideFeedback
import com.trapezo.pos.ui.components.ResponsiveScope
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.TrapezoSnackbarHost
import com.trapezo.pos.ui.components.isExpanded
import com.trapezo.pos.ui.screens.CustomersScreen
import com.trapezo.pos.ui.screens.DashboardScreen
import com.trapezo.pos.ui.screens.InventoryScreen
import com.trapezo.pos.ui.screens.PosScreen
import com.trapezo.pos.ui.screens.ProductsScreen
import com.trapezo.pos.ui.screens.ReportsScreen
import com.trapezo.pos.ui.screens.SettingsScreen
import com.trapezo.pos.ui.screens.ShiftScreen
import com.trapezo.pos.ui.screens.TransactionsScreen
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import kotlinx.coroutines.launch

@Composable
fun TrapezoRoot(vm: AppViewModel = viewModel()) {
    val session by vm.session.collectAsState()
    when {
        // A database that cannot be opened must never crash or spin forever.
        session.fatalStartupError != null -> StartupRecoveryScreen(
            onRetry = vm::retryStartup,
            onRecovered = vm::reinitializeAfterRecovery
        )
        session.initializing -> Box(Modifier.fillMaxSize()) { LoadingState("Menyiapkan Trapezo POS…") }
        session.needsSetup -> OwnerSetupScreen(session.loading, session.error, session.notice, vm::setupOwner)
        session.user == null -> LoginScreen(session.loading, session.error, session.notice, vm::login)
        else -> MainShell(
            user = session.user!!,
            onLogout = vm::logout,
            onSessionRefresh = vm::refreshCurrentSession,
            onSessionReauth = vm::forceReauthAfterRestore
        )
    }
}

/**
 * Startup recovery surface, shown when the local database cannot be opened.
 *
 * Offers exactly two non-destructive actions: retry, or restore a real Trapezo POS backup
 * through the real [BackupService] via SAF. There is deliberately NO "erase all data" shortcut
 * and no destructive migration.
 */
@Composable
private fun StartupRecoveryScreen(
    onRetry: () -> Unit,
    onRecovered: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    if (pendingUri != null) {
        val uri = pendingUri!!
        ConfirmActionDialog(
            title = "Pulihkan dari file backup?",
            message = "Data saat ini akan digantikan setelah file backup diverifikasi. " +
                "Anda diminta masuk kembali menggunakan akun dari backup setelah proses selesai.",
            confirmLabel = "Pulihkan",
            tone = Tone.DANGER,
            onDismiss = { pendingUri = null },
            onConfirm = {
                pendingUri = null
                busy = true
                failure = null
                scope.launch {
                    val result = try {
                        BackupService(context).restoreFrom(uri)
                    } catch (t: Throwable) {
                        BackupService.BackupResult(false, "Restore gagal. File backup tidak dapat diproses.")
                    }
                    busy = false
                    if (result.ok) {
                        onRecovered("Pemulihan berhasil. Silakan masuk kembali menggunakan akun dari backup.")
                    } else {
                        // Stay on the recovery screen and report the real, safe error.
                        failure = result.message
                    }
                }
            }
        )
    }

    AuthSurface {
        Text(
            "Data Trapezo POS tidak dapat dibuka",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            "Database lokal tidak dapat dibaca. Anda dapat mencoba lagi atau memulihkan " +
                "backup Trapezo POS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        failure?.let {
            Spacer(Modifier.height(Space.md))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(Space.lg))
        Button(
            onClick = onRetry,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.min)
        ) { Text(if (busy) "Memproses…" else "Coba Lagi") }
        Spacer(Modifier.height(Space.sm))
        OutlinedButton(
            onClick = {
                failure = null
                picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-sqlite3", "*/*"))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.min)
        ) { Text("Pulihkan Backup") }
    }
}

/** Compact, professional authentication surface — no oversized branding art. */
@Composable
private fun AuthSurface(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.xxl)
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = Radius.panel,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(Space.xxl)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                content = content
            )
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction = ImeAction.Done
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = Radius.field,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Sembunyikan password" else "Tampilkan password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OwnerSetupScreen(
    loading: Boolean,
    error: String?,
    notice: String?,
    onSetup: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    AuthSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TrapezoMark(size = 32.dp)
            Column {
                Text("Setup Pemilik", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Langkah pertama perangkat ini",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Buat akun administrator pertama. Tidak ada kredensial bawaan pada Trapezo POS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(name, { name = it }, label = { Text("Nama pemilik") }, singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, shape = Radius.field, modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it }, "Password (min. 8 karakter)", ImeAction.Next)
        PasswordField(confirm, { confirm = it }, "Ulangi password")
        (localError ?: error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                localError = when {
                    password != confirm -> "Konfirmasi password tidak sama"
                    name.isBlank() -> "Nama pemilik wajib diisi"
                    username.trim().length < 3 -> "Username minimal 3 karakter"
                    password.length < 8 -> "Password minimal 8 karakter"
                    else -> null
                }
                if (localError == null) onSetup(username, name, password)
            },
            enabled = !loading,
            shape = Radius.field,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
        ) { Text(if (loading) "Menyimpan…" else "Buat Akun Pemilik", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, notice: String?, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TrapezoMark(size = 32.dp)
            Column {
                Text("Trapezo POS", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Masuk untuk mulai bertransaksi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedTextField(
            username,
            { username = it },
            label = { Text("Username") },
            singleLine = true,
            shape = Radius.field,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        PasswordField(password, { password = it }, "Password")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = { onLogin(username, password) },
            enabled = !loading,
            shape = Radius.field,
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
        ) { Text(if (loading) "Memeriksa…" else "Masuk", fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    user: UserEntity,
    onLogout: () -> Unit,
    onSessionRefresh: () -> Unit,
    onSessionReauth: (String?) -> Unit
) {
    val destinations = remember(user.role) { Navigation.destinationsFor(user.role) }
    val compactPrimary = remember(user.role) { Navigation.compactPrimary(user.role) }
    val overflow = remember(user.role) { Navigation.compactOverflow(user.role) }
    // Keyed on both id and role so a role change re-routes to the new role's start
    // destination immediately instead of leaving a now-unauthorized screen selected.
    var destination by remember(user.id, user.role) { mutableStateOf(Navigation.startDestination(user.role)) }
    var overflowOpen by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ProvideFeedback(snackbarHost) {
        ResponsiveScope(Modifier.fillMaxSize()) { widthClass ->
            Row(Modifier.fillMaxSize()) {
                if (widthClass.isExpanded) {
                    AppNavigationRail(
                        destinations = destinations,
                        current = destination,
                        userName = user.name,
                        role = user.role,
                        onSelect = { destination = it },
                        onLogout = onLogout
                    )
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { TrapezoSnackbarHost(snackbarHost) },
                    bottomBar = {
                        if (!widthClass.isExpanded) {
                            AppBottomNavigation(
                                primary = compactPrimary,
                                current = destination,
                                hasOverflow = overflow.isNotEmpty(),
                                onSelect = { destination = it },
                                onOverflow = { overflowOpen = true }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Crossfade(
                            targetState = destination,
                            animationSpec = tween(180),
                            label = "destination"
                        ) { current ->
                            when (current) {
                                AppDestination.DASHBOARD -> DashboardScreen(user)
                                AppDestination.POS -> PosScreen(user)
                                AppDestination.SHIFT -> ShiftScreen(user)
                                AppDestination.PRODUCTS -> ProductsScreen(user.id, user.role == "ADMIN")
                                AppDestination.INVENTORY -> InventoryScreen(user.id, user.role == "ADMIN")
                                AppDestination.TRANSACTIONS -> TransactionsScreen(user)
                                AppDestination.CUSTOMERS -> CustomersScreen(user)
                                AppDestination.REPORTS -> ReportsScreen()
                                AppDestination.SETTINGS -> SettingsScreen(user, onSessionRefresh, onSessionReauth)
                            }
                        }
                    }
                }
            }
        }

        if (overflowOpen) {
            ModalBottomSheet(
                onDismissRequest = { overflowOpen = false },
                sheetState = sheetState
            ) {
                Column(Modifier.padding(bottom = Space.xxl).navigationBarsPadding()) {
                    Text(
                        "Menu lainnya",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.md)
                    )
                    overflow.forEach { item ->
                        ListItem(
                            headlineContent = { Text(item.label) },
                            leadingContent = { Icon(item.icon, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Touch.control)
                                .clickable { destination = item; overflowOpen = false }
                        )
                    }
                    ListItem(
                        headlineContent = { Text("Keluar") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Touch.control)
                            .clickable { overflowOpen = false; onLogout() }
                    )
                }
            }
        }
    }
}
