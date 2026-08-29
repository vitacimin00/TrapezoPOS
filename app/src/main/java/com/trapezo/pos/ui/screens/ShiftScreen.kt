package com.trapezo.pos.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CashMovementEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.ShiftRepository
import com.trapezo.pos.ui.components.AmountRow
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MoneyText
import com.trapezo.pos.ui.components.ScreenHeader
import com.trapezo.pos.ui.components.SectionHeader
import com.trapezo.pos.ui.components.StatusBadge
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.rememberFeedback
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space
import com.trapezo.pos.ui.theme.Touch
import com.trapezo.pos.ui.theme.TrapezoStatus
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.launch

/**
 * Dedicated shift context. Shift work no longer competes with the POS transaction
 * surface: opening, cash movement, and closing all live here with explicit numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftScreen(user: UserEntity) {
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    var shift by remember { mutableStateOf<ShiftEntity?>(null) }
    var movements by remember { mutableStateOf(emptyList<CashMovementEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var openSheet by remember { mutableStateOf(false) }
    var cashSheet by remember { mutableStateOf<String?>(null) }
    var closeSheet by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val active = AppGraph.db.shiftDao().openShiftForUser(user.id)
            shift = active
            movements = active?.let { AppGraph.db.shiftDao().cashMovements(it.id) }.orEmpty()
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Shift",
            subtitle = "${user.name} • ${Labels.role(user.role)}",
            actions = {
                if (shift != null) {
                    StatusBadge("Aktif", Tone.SUCCESS, Icons.Default.Schedule)
                } else {
                    StatusBadge("Tidak aktif", Tone.NEUTRAL, Icons.Default.Schedule)
                }
            }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            loading -> LoadingState("Memuat shift…")
            shift == null -> EmptyState(
                title = "Belum ada shift aktif",
                message = "Buka shift dan masukkan kas awal sebelum menerima pembayaran di Kasir.",
                icon = Icons.Default.LockOpen,
                actionLabel = "Buka Shift",
                onAction = { openSheet = true }
            )
            else -> {
                val active = shift!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.lg)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = Radius.card,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SectionHeader("Ringkasan kas")
                            Text(
                                "Dibuka ${Dates.dmyhm(active.openedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AmountRow("Kas awal", active.openingCash)
                            AmountRow("Penjualan tunai", active.totalCashSales)
                            AmountRow("Penjualan non-tunai", active.totalNonCashSales)
                            AmountRow("Kas masuk", active.cashIn, tone = Tone.SUCCESS)
                            AmountRow("Kas keluar", -active.cashOut, tone = Tone.WARNING)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AmountRow("Kas seharusnya", active.expectedCash, emphasize = true)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { cashSheet = "CASH_IN" },
                            shape = Radius.field,
                            modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Kas Masuk")
                        }
                        OutlinedButton(
                            onClick = { cashSheet = "CASH_OUT" },
                            shape = Radius.field,
                            modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Kas Keluar")
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SectionHeader("Riwayat kas shift ini")
                        if (movements.isEmpty()) {
                            Text(
                                "Belum ada kas masuk atau kas keluar pada shift ini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            movements.forEach { movement ->
                                val isIn = movement.type == "CASH_IN"
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        tint = if (isIn) TrapezoStatus.success else TrapezoStatus.warning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    Column(Modifier.weight(1f)) {
                                        Text(Labels.cashMovement(movement.type), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${Dates.dmyhm(movement.createdAt)}${if (movement.note.isBlank()) "" else " • ${movement.note}"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    MoneyText(
                                        if (isIn) movement.amount else -movement.amount,
                                        color = if (isIn) TrapezoStatus.success else TrapezoStatus.warning,
                                        weight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { closeSheet = true },
                        shape = Radius.field,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
                    ) { Text("Tutup Shift", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (openSheet) {
        OpenShiftSheet(
            user = user,
            onDismiss = { openSheet = false },
            onOpened = { message -> openSheet = false; feedback?.success(message); reload() },
            onError = { feedback?.error(it) }
        )
    }
    cashSheet?.let { type ->
        shift?.let { active ->
            CashMovementSheet(
                shift = active,
                type = type,
                user = user,
                onDismiss = { cashSheet = null },
                onSaved = { message -> cashSheet = null; feedback?.success(message); reload() }
            )
        }
    }
    if (closeSheet) {
        shift?.let { active ->
            CloseShiftSheet(
                shift = active,
                user = user,
                onDismiss = { closeSheet = false },
                onClosed = { message -> closeSheet = false; feedback?.success(message); reload() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenShiftSheet(
    user: UserEntity,
    onDismiss: () -> Unit,
    onOpened: (String) -> Unit,
    onError: (String) -> Unit
) {
    var opening by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = Money.parseOrNull(opening, allowBlank = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Buka Shift", style = MaterialTheme.typography.titleMedium)
            Text(
                "Kasir: ${user.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = opening,
                onValueChange = { opening = it; error = null },
                label = { Text("Kas awal") },
                placeholder = { Text("0") },
                prefix = { Text("Rp ") },
                singleLine = true,
                shape = Radius.field,
                isError = error != null,
                supportingText = {
                    Text(
                        error ?: "Nominal dibulatkan ke rupiah utuh.",
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            if (parsed != null) AmountRow("Kas awal tercatat", parsed)
            Button(
                onClick = {
                    val cash = Money.parseOrNull(opening, allowBlank = true)
                    if (cash == null) error = "Kas awal tidak valid"
                    else {
                        saving = true
                        scope.launch {
                            when (val result = AppGraph.shifts.open(user, cash)) {
                                is ShiftRepository.Result.Ok -> onOpened("Shift dibuka dengan kas awal ${Money.fmt(cash)}")
                                is ShiftRepository.Result.Error -> { error = result.message; onError(result.message) }
                            }
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text(if (saving) "Membuka…" else "Buka Shift", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashMovementSheet(
    shift: ShiftEntity,
    type: String,
    user: UserEntity,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label = Labels.cashMovement(type)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            AmountRow("Kas seharusnya saat ini", shift.expectedCash)
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it; error = null },
                label = { Text("Jumlah") },
                prefix = { Text("Rp ") },
                singleLine = true,
                shape = Radius.field,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it; error = null },
                label = { Text("Alasan") },
                shape = Radius.field,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    val cash = Money.parseOrNull(amount)
                    if (cash == null || cash <= 0) error = "Jumlah harus lebih besar dari nol"
                    else {
                        saving = true
                        scope.launch {
                            when (val result = AppGraph.shifts.cash(shift, type, cash, reason, user.id)) {
                                is ShiftRepository.Result.Ok -> onSaved("$label ${Money.fmt(cash)} tersimpan")
                                is ShiftRepository.Result.Error -> error = result.message
                            }
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text(if (saving) "Menyimpan…" else "Simpan $label", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloseShiftSheet(
    shift: ShiftEntity,
    user: UserEntity,
    onDismiss: () -> Unit,
    onClosed: (String) -> Unit
) {
    var actual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val expected = shift.expectedCash
    val parsedActual = Money.parseOrNull(actual, allowBlank = true)
    val difference = parsedActual?.let { Money.subtractExact(it, expected) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Tutup Shift", style = MaterialTheme.typography.titleMedium)
            AmountRow("Kas awal", shift.openingCash)
            AmountRow("Penjualan tunai", shift.totalCashSales)
            AmountRow("Kas masuk", shift.cashIn)
            AmountRow("Kas keluar", -shift.cashOut)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AmountRow("Kas seharusnya", expected, emphasize = true)
            OutlinedTextField(
                value = actual,
                onValueChange = { actual = it; error = null },
                label = { Text("Kas aktual hasil hitung") },
                prefix = { Text("Rp ") },
                singleLine = true,
                shape = Radius.field,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            if (difference != null) {
                val tone = when {
                    difference == 0L -> Tone.SUCCESS
                    difference > 0L -> Tone.INFO
                    else -> Tone.DANGER
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    StatusBadge(
                        when {
                            difference == 0L -> "Kas sesuai"
                            difference > 0L -> "Kas lebih"
                            else -> "Kas kurang"
                        },
                        tone
                    )
                    AmountRow("Selisih", difference, tone = tone)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    if (Money.parseOrNull(actual) == null) error = "Kas aktual tidak valid"
                    else confirm = true
                },
                enabled = !saving,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text(if (saving) "Menutup…" else "Tutup Shift", fontWeight = FontWeight.Bold) }
        }
    }

    if (confirm) {
        ConfirmActionDialog(
            title = "Tutup shift sekarang?",
            message = "Shift akan ditutup dengan selisih ${Money.fmt(difference ?: 0L)}. Tindakan ini tidak dapat dibatalkan.",
            confirmLabel = "Tutup Shift",
            tone = Tone.WARNING,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                val cash = Money.parseOrNull(actual)
                if (cash == null) error = "Kas aktual tidak valid"
                else {
                    saving = true
                    scope.launch {
                        when (val result = AppGraph.shifts.close(shift, cash, user.id)) {
                            is ShiftRepository.Result.Ok ->
                                onClosed("Shift ditutup. Selisih ${Money.fmt(result.shift.difference)}")
                            is ShiftRepository.Result.Error -> error = result.message
                        }
                        saving = false
                    }
                }
            }
        )
    }
}
