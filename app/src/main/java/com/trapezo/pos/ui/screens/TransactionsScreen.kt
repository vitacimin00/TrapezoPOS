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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.PaymentMethodEntity
import com.trapezo.pos.data.entity.RefundEntity
import com.trapezo.pos.data.entity.RefundItemEntity
import com.trapezo.pos.data.entity.RefundPaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.RefundRepository
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.domain.model.OperationalInputRules
import com.trapezo.pos.domain.model.RefundPreview
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.printer.ReceiptService
import com.trapezo.pos.printer.receiptInfo
import com.trapezo.pos.ui.components.AmountRow
import com.trapezo.pos.ui.components.ConfirmActionDialog
import com.trapezo.pos.ui.components.EmptyState
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
import com.trapezo.pos.ui.theme.TrapezoStatus
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tone mapping for a transaction status; label always accompanies the color. */
private fun statusTone(status: String): Tone = when (status.uppercase()) {
    "COMPLETED" -> Tone.SUCCESS
    "PARTIALLY_REFUNDED" -> Tone.WARNING
    "REFUNDED" -> Tone.DANGER
    "VOID" -> Tone.NEUTRAL
    else -> Tone.NEUTRAL
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(user: UserEntity) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val feedback = rememberFeedback()
    val widthClass = currentWidthClass

    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var method by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var fromMs by remember { mutableStateOf<Long?>(null) }
    var toMs by remember { mutableStateOf<Long?>(null) }
    var cashierId by remember { mutableStateOf<Long?>(null) }
    var cashiers by remember { mutableStateOf(emptyList<UserEntity>()) }
    var paymentMethods by remember { mutableStateOf(emptyList<PaymentMethodEntity>()) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var sales by remember { mutableStateOf(emptyList<SaleEntity>()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var requestVersion by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }

    fun resetResults() { page = 0; requestVersion++ }

    LaunchedEffect(query) { delay(300); debouncedQuery = query; resetResults() }
    LaunchedEffect(debouncedQuery, method, status, page, fromMs, toMs, cashierId, requestVersion) {
        val requestedPage = page
        val version = requestVersion
        if (requestedPage == 0) loading = true else loadingMore = true
        val filters = SalesRepository.HistoryFilters(
            fromMs = fromMs, toMs = toMs, cashierUserId = cashierId,
            method = method, status = status, queryInvoice = debouncedQuery
        )
        val result = AppGraph.sales.history(filters, requestedPage)
        if (requestedPage != page || version != requestVersion) return@LaunchedEffect
        sales = if (requestedPage == 0) result.first else (sales + result.first).distinctBy { it.id }
        total = result.second
        loading = false
        loadingMore = false
    }
    LaunchedEffect(Unit) {
        cashiers = AppGraph.users.all()
        paymentMethods = AppGraph.db.paymentMethodDao().all()
    }

    val activeFilterCount = listOf(
        method != null, status != null, fromMs != null, toMs != null, cashierId != null
    ).count { it }

    fun shareReceipt(sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>) {
        scope.launch {
            val receipt = ReceiptService(context)
            val file = receipt.createPdf(receiptInfo(AppGraph.store, AppGraph.settings), sale, items, payments)
            receipt.sharePdf(file)
        }
    }
    fun printReceipt(sale: SaleEntity, items: List<SaleItemEntity>, payments: List<PaymentEntity>) {
        scope.launch {
            val address = AppGraph.settings.raw("printer.address", "")
            val info = receiptInfo(AppGraph.store, AppGraph.settings)
            when (val result = BluetoothPrinterService(context).print(
                address, ReceiptService(context).escPosBytes(info, sale, items, payments)
            )) {
                is BluetoothPrinterService.Result.Success -> feedback?.success("Struk dikirim ke printer")
                is BluetoothPrinterService.Result.Error ->
                    feedback?.error("${result.message}. Gunakan Bagikan PDF sebagai alternatif.")
            }
        }
    }

    val listPane: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                SearchField(query, { query = it }, "Cari nomor invoice")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    AssistChip(
                        onClick = { filtersOpen = true },
                        label = { Text(if (activeFilterCount > 0) "Filter • $activeFilterCount" else "Filter") },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    if (activeFilterCount > 0) {
                        TextButton(onClick = {
                            method = null; status = null; fromMs = null; toMs = null; cashierId = null; resetResults()
                        }) { Text("Reset") }
                    }
                }
                if (activeFilterCount > 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        method?.let { StatusBadge(Labels.paymentMethod(it), Tone.INFO) }
                        status?.let { StatusBadge(Labels.transactionStatus(it), statusTone(it)) }
                        fromMs?.let { StatusBadge("Dari ${Dates.dmy(it)}", Tone.INFO) }
                        toMs?.let { StatusBadge("Sampai ${Dates.dmy(it)}", Tone.INFO) }
                        cashierId?.let { id ->
                            cashiers.firstOrNull { it.id == id }?.let { StatusBadge("Kasir: ${it.name}", Tone.INFO) }
                        }
                    }
                }
            }
            when {
                loading -> LoadingState("Memuat riwayat transaksi…")
                sales.isEmpty() && debouncedQuery.isNotBlank() -> EmptyState(
                    title = "Tidak ada hasil",
                    message = "Tidak ada transaksi dengan nomor invoice \"$debouncedQuery\".",
                    icon = Icons.Default.ReceiptLong
                )
                sales.isEmpty() && activeFilterCount > 0 -> EmptyState(
                    title = "Tidak ada transaksi pada filter ini",
                    message = "Ubah rentang tanggal atau filter lain untuk melihat transaksi.",
                    icon = Icons.Default.Tune,
                    actionLabel = "Reset filter",
                    onAction = { method = null; status = null; fromMs = null; toMs = null; cashierId = null; resetResults() }
                )
                sales.isEmpty() -> EmptyState(
                    title = "Belum ada transaksi",
                    message = "Transaksi yang diselesaikan di Kasir akan muncul di sini.",
                    icon = Icons.Default.ReceiptLong
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Space.lg, end = Space.lg, bottom = Space.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(sales, key = { it.id }) { sale ->
                        TransactionRow(
                            sale = sale,
                            selected = selectedId == sale.id && widthClass.isExpanded,
                            onClick = { selectedId = sale.id }
                        )
                    }
                    if (sales.size < total) {
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
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Transaksi", subtitle = "$total transaksi sesuai filter")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (widthClass.isExpanded) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.45f)) { listPane() }
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(0.55f)) {
                    val id = selectedId
                    if (id == null) {
                        EmptyState(
                            title = "Pilih transaksi",
                            message = "Pilih satu transaksi di sebelah kiri untuk melihat detail, pembayaran, dan riwayat refund.",
                            icon = Icons.Default.ReceiptLong
                        )
                    } else {
                        TransactionDetailPane(
                            saleId = id,
                            user = user,
                            onClose = null,
                            onRefunded = { message -> feedback?.success(message); resetResults() },
                            onShare = ::shareReceipt,
                            onPrint = ::printReceipt
                        )
                    }
                }
            }
        } else {
            listPane()
        }
    }

    if (!widthClass.isExpanded) {
        selectedId?.let { id ->
            ModalBottomSheet(
                onDismissRequest = { selectedId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Box(Modifier.fillMaxHeight(0.92f)) {
                    TransactionDetailPane(
                        saleId = id,
                        user = user,
                        onClose = { selectedId = null },
                        onRefunded = { message -> selectedId = null; feedback?.success(message); resetResults() },
                        onShare = ::shareReceipt,
                        onPrint = ::printReceipt
                    )
                }
            }
        }
    }

    if (filtersOpen) {
        TransactionFilterSheet(
            paymentMethods = paymentMethods,
            cashiers = cashiers,
            method = method,
            status = status,
            cashierId = cashierId,
            fromMs = fromMs,
            toMs = toMs,
            onMethod = { method = it; resetResults() },
            onStatus = { status = it; resetResults() },
            onCashier = { cashierId = it; resetResults() },
            onPickFrom = { pickFrom = true },
            onPickTo = { pickTo = true },
            onClearDates = { fromMs = null; toMs = null; resetResults() },
            onDismiss = { filtersOpen = false }
        )
    }

    if (pickFrom) {
        val state = rememberDatePickerState(initialSelectedDateMillis = fromMs)
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    fromMs = state.selectedDateMillis?.let { Dates.startOfDay(it) }
                    resetResults(); pickFrom = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("Batal") } }
        ) { DatePicker(state = state) }
    }
    if (pickTo) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toMs)
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = {
                TextButton(onClick = {
                    toMs = state.selectedDateMillis?.let { Dates.endOfDay(it) }
                    resetResults(); pickTo = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("Batal") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun TransactionRow(sale: SaleEntity, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = Radius.card,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    sale.invoiceNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Dates.dmyhm(sale.createdAt)} • ${sale.userNameSnapshot}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusBadge(Labels.transactionStatus(sale.transactionStatus), statusTone(sale.transactionStatus))
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    sale.grandTotal,
                    style = MaterialTheme.typography.bodyLarge,
                    weight = FontWeight.Bold
                )
                Text(
                    Labels.paymentStatus(sale.paymentStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class TransactionDetail(
    val sale: SaleEntity,
    val items: List<SaleItemEntity>,
    val payments: List<PaymentEntity>,
    val refunds: List<RefundEntity>,
    val refundItems: Map<Long, List<RefundItemEntity>>,
    val refundPayments: Map<Long, List<RefundPaymentEntity>>
)

/** Structured transaction detail with refund timeline; never a raw entity dump. */
@Composable
private fun TransactionDetailPane(
    saleId: Long,
    user: UserEntity,
    onClose: (() -> Unit)?,
    onRefunded: (String) -> Unit,
    onShare: (SaleEntity, List<SaleItemEntity>, List<PaymentEntity>) -> Unit,
    onPrint: (SaleEntity, List<SaleItemEntity>, List<PaymentEntity>) -> Unit
) {
    var detail by remember(saleId) { mutableStateOf<TransactionDetail?>(null) }
    var refundOpen by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(saleId, reloadKey) {
        val base = AppGraph.sales.saleWithDetails(saleId)
        if (base != null) {
            val refunds = AppGraph.db.refundDao().refundsForSale(saleId)
            detail = TransactionDetail(
                sale = base.first,
                items = base.second,
                payments = base.third,
                refunds = refunds,
                refundItems = refunds.associate { it.id to AppGraph.db.refundDao().itemsFor(it.id) },
                refundPayments = refunds.associate { it.id to AppGraph.db.refundDao().paymentsFor(it.id) }
            )
        }
    }

    val data = detail
    if (data == null) {
        LoadingState("Memuat detail transaksi…")
        return
    }
    val sale = data.sale
    val itemNames = data.items.associate { it.id to it.productNameSnapshot }
    val canRefund = user.role == "ADMIN" &&
        (sale.transactionStatus == "COMPLETED" || sale.transactionStatus == "PARTIALLY_REFUNDED")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(sale.invoiceNumber, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Dates.dmyhm(sale.createdAt)} • ${sale.userNameSnapshot}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusBadge(Labels.transactionStatus(sale.transactionStatus), statusTone(sale.transactionStatus))
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Tutup detail") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            sale.customerNameSnapshot?.takeIf { it.isNotBlank() }?.let { name ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SectionHeader("Customer")
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Item")
                data.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.productNameSnapshot,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${item.quantity} × ${Money.fmt(item.unitPrice)}" +
                                    if (item.discount > 0) " • diskon ${Money.fmt(item.discount)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MoneyText(item.subtotal, weight = FontWeight.Medium)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SectionHeader("Ringkasan")
                AmountRow("Subtotal", sale.subtotal)
                if (sale.discount > 0) AmountRow("Diskon", -sale.discount, tone = Tone.WARNING)
                if (sale.tax > 0) AmountRow("Pajak", sale.tax)
                if (sale.serviceCharge > 0) AmountRow("Service charge", sale.serviceCharge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AmountRow("Total", sale.grandTotal, emphasize = true)
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SectionHeader("Pembayaran")
                data.payments.forEach { payment ->
                    AmountRow(Labels.paymentMethod(payment.method), payment.amount)
                }
                if (sale.changeAmount > 0) AmountRow("Kembalian", sale.changeAmount, tone = Tone.SUCCESS)
            }

            if (data.refunds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    SectionHeader("Riwayat refund")
                    data.refunds.forEachIndexed { index, refund ->
                        Surface(
                            color = TrapezoStatus.dangerContainer,
                            shape = Radius.card,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Replay,
                                        contentDescription = null,
                                        tint = TrapezoStatus.danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(Space.xs))
                                    Text(
                                        "Refund #${index + 1} • ${Dates.dmyhm(refund.createdAt)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TrapezoStatus.danger,
                                        modifier = Modifier.weight(1f)
                                    )
                                    MoneyText(
                                        refund.total,
                                        color = TrapezoStatus.danger,
                                        weight = FontWeight.Bold
                                    )
                                }
                                data.refundItems[refund.id]?.forEach { line ->
                                    Text(
                                        "${line.quantity} × ${itemNames[line.saleItemId] ?: "Item #${line.saleItemId}"} — ${Money.fmt(line.amount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrapezoStatus.danger
                                    )
                                }
                                data.refundPayments[refund.id]?.takeIf { it.isNotEmpty() }?.let { reversals ->
                                    Text(
                                        "Pengembalian: " + reversals.joinToString(", ") {
                                            "${Labels.paymentMethod(it.method)} ${Money.fmt(it.amount)}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrapezoStatus.danger
                                    )
                                }
                                if (refund.reason.isNotBlank()) {
                                    Text(
                                        "Alasan: ${refund.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrapezoStatus.danger
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .padding(horizontal = Space.lg, vertical = Space.md)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            OutlinedButton(
                onClick = { onPrint(sale, data.items, data.payments) },
                shape = Radius.field,
                modifier = Modifier.weight(1f).heightIn(min = Touch.control)
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Cetak")
            }
            OutlinedButton(
                onClick = { onShare(sale, data.items, data.payments) },
                shape = Radius.field,
                modifier = Modifier.weight(1f).heightIn(min = Touch.control)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Bagikan")
            }
            if (canRefund) {
                Button(
                    onClick = { refundOpen = true },
                    shape = Radius.field,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = TrapezoStatus.danger),
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Refund")
                }
            }
        }
    }

    if (refundOpen) {
        RefundSheet(
            sale = sale,
            items = data.items,
            user = user,
            onDismiss = { refundOpen = false },
            onResult = { message -> refundOpen = false; reloadKey++; onRefunded(message) }
        )
    }
}

/**
 * Refund sheet. Selection starts empty, each checked row must carry qty >= 1 and
 * <= remaining, and the preview/allocation numbers come from the locked refund domain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefundSheet(
    sale: SaleEntity,
    items: List<SaleItemEntity>,
    user: UserEntity,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RefundRepository.PreviewState?>(null) }
    var quantities by remember { mutableStateOf(emptyMap<Long, String>()) }
    var selected by remember { mutableStateOf(emptyMap<Long, Boolean>()) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }

    LaunchedEffect(sale.id) {
        state = AppGraph.refunds.previewState(sale.id)
        quantities = state?.lines?.associate { it.saleItemId to "" }.orEmpty()
        selected = state?.lines?.associate { it.saleItemId to false }.orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val previewState = state
        if (previewState == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) { LoadingState("Memuat data refund…") }
            return@ModalBottomSheet
        }
        val requested = previewState.lines.associate { line ->
            line.saleItemId to if (selected[line.saleItemId] == true) {
                quantities[line.saleItemId]?.toLongOrNull() ?: 0L
            } else 0L
        }
        val preview = RefundPreview.preview(
            previewState.sale.grandTotal,
            previewState.alreadyRefundedTotal,
            previewState.lines,
            requested
        )
        val valid = reason.isNotBlank() && preview.currentRefundTotal > 0L &&
            OperationalInputRules.validRefundSelection(
                selected, requested, previewState.lines.associate { it.saleItemId to it.remainingQuantity }
            )

        Column(
            Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = Space.xl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Refund Transaksi", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${sale.invoiceNumber} • hanya administrator",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge("Administrator", Tone.INFO)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    AmountRow("Total transaksi", preview.saleGrandTotal)
                    AmountRow("Sudah direfund", preview.alreadyRefundedTotal, tone = Tone.WARNING)
                    AmountRow("Sisa dapat direfund", preview.remainingSaleValue)
                }
                SectionHeader("Pilih item yang direfund")
                previewState.lines.forEach { line ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = Radius.card,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(Space.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected[line.saleItemId] ?: false,
                                enabled = !line.fullyRefunded,
                                onCheckedChange = { checked ->
                                    selected = selected.toMutableMap().apply { put(line.saleItemId, checked) }
                                    quantities = quantities.toMutableMap().apply {
                                        put(line.saleItemId, if (checked) line.remainingQuantity.toString() else "")
                                    }
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    line.productName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Terjual ${line.soldQuantity} • direfund ${line.alreadyRefundedQuantity} • sisa ${line.remainingQuantity}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Nilai tersisa ${Money.fmt(line.remainingRefundableAmount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (line.fullyRefunded) StatusBadge("Sudah direfund penuh", Tone.NEUTRAL)
                            }
                            OutlinedTextField(
                                value = quantities[line.saleItemId].orEmpty(),
                                onValueChange = { value ->
                                    quantities = quantities.toMutableMap().apply {
                                        put(line.saleItemId, value.filter(Char::isDigit))
                                    }
                                },
                                label = { Text("Qty") },
                                enabled = !line.fullyRefunded && selected[line.saleItemId] == true,
                                singleLine = true,
                                shape = Radius.field,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(96.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it; error = null },
                    label = { Text("Alasan refund *") },
                    shape = Radius.field,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AmountRow("Total refund", preview.currentRefundTotal, emphasize = true, tone = Tone.DANGER)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text("Batal") }
                Button(
                    onClick = { confirm = true },
                    enabled = valid && !processing,
                    shape = Radius.field,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = TrapezoStatus.danger),
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) { Text(if (processing) "Memproses…" else "Lanjutkan", fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (confirm) {
        val previewState = state
        val requested = previewState?.lines?.associate { line ->
            line.saleItemId to if (selected[line.saleItemId] == true) {
                quantities[line.saleItemId]?.toLongOrNull() ?: 0L
            } else 0L
        }.orEmpty()
        val refundTotal = previewState?.let {
            RefundPreview.preview(it.sale.grandTotal, it.alreadyRefundedTotal, it.lines, requested).currentRefundTotal
        } ?: 0L
        ConfirmActionDialog(
            title = "Proses refund sekarang?",
            message = "Refund ${Money.fmt(refundTotal)} akan diproses, stok dikembalikan, dan kas shift disesuaikan. Tindakan ini tidak dapat dibatalkan.",
            confirmLabel = "Proses Refund",
            tone = Tone.DANGER,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                processing = true
                val itemById = items.associateBy { it.id }
                val rows = requested.filterValues { it > 0 }.mapNotNull { (id, qty) ->
                    itemById[id]?.let { RefundRepository.RequestedItem(it, qty) }
                }
                scope.launch {
                    when (val result = AppGraph.refunds.refund(sale.id, user.id, rows, reason)) {
                        is RefundRepository.Result.Success ->
                            onResult("Refund ${Money.fmt(result.total)} berhasil; stok dikembalikan")
                        is RefundRepository.Result.Error -> error = result.message
                    }
                    processing = false
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFilterSheet(
    paymentMethods: List<PaymentMethodEntity>,
    cashiers: List<UserEntity>,
    method: String?,
    status: String?,
    cashierId: Long?,
    fromMs: Long?,
    toMs: Long?,
    onMethod: (String?) -> Unit,
    onStatus: (String?) -> Unit,
    onCashier: (Long?) -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onClearDates: () -> Unit,
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
            Text("Filter Transaksi", style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Rentang tanggal")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = fromMs == null && toMs == null,
                        onClick = onClearDates,
                        label = { Text("Semua tanggal") },
                        shape = Radius.control
                    )
                    FilterChip(
                        selected = fromMs != null,
                        onClick = onPickFrom,
                        label = { Text(if (fromMs != null) "Dari ${Dates.dmy(fromMs)}" else "Pilih tanggal awal") },
                        shape = Radius.control
                    )
                    FilterChip(
                        selected = toMs != null,
                        onClick = onPickTo,
                        label = { Text(if (toMs != null) "Sampai ${Dates.dmy(toMs)}" else "Pilih tanggal akhir") },
                        shape = Radius.control
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Metode pembayaran")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = method == null,
                        onClick = { onMethod(null) },
                        label = { Text("Semua") },
                        shape = Radius.control
                    )
                    paymentMethods.forEach { payment ->
                        FilterChip(
                            selected = method == payment.type,
                            onClick = { onMethod(payment.type) },
                            label = { Text(payment.name) },
                            shape = Radius.control
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Status transaksi")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    FilterChip(
                        selected = status == null,
                        onClick = { onStatus(null) },
                        label = { Text("Semua") },
                        shape = Radius.control
                    )
                    listOf("COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED").forEach { code ->
                        FilterChip(
                            selected = status == code,
                            onClick = { onStatus(code) },
                            label = { Text(Labels.transactionStatus(code)) },
                            shape = Radius.control
                        )
                    }
                }
            }

            if (cashiers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SectionHeader("Kasir")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilterChip(
                            selected = cashierId == null,
                            onClick = { onCashier(null) },
                            label = { Text("Semua") },
                            shape = Radius.control
                        )
                        cashiers.forEach { cashier ->
                            FilterChip(
                                selected = cashierId == cashier.id,
                                onClick = { onCashier(cashier.id) },
                                label = { Text(cashier.name, maxLines = 1) },
                                shape = Radius.control
                            )
                        }
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
