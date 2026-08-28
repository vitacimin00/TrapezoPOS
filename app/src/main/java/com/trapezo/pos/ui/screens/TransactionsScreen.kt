package com.trapezo.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.RefundRepository
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.domain.model.RefundPreview
import com.trapezo.pos.domain.model.OperationalInputRules
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.printer.ReceiptService
import com.trapezo.pos.printer.receiptInfo
import com.trapezo.pos.utils.Dates
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(user: UserEntity) {
    val scope = rememberCoroutineScope(); val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }; var method by remember { mutableStateOf<String?>(null) }; var status by remember { mutableStateOf<String?>(null) }
    var fromMs by remember { mutableStateOf<Long?>(null) }; var toMs by remember { mutableStateOf<Long?>(null) }
    var cashierId by remember { mutableStateOf<Long?>(null) }; var cashiers by remember { mutableStateOf(emptyList<UserEntity>()) }
    var paymentMethods by remember { mutableStateOf(emptyList<com.trapezo.pos.data.entity.PaymentMethodEntity>()) }
    var pickFrom by remember { mutableStateOf(false) }; var pickTo by remember { mutableStateOf(false) }
    var sales by remember { mutableStateOf(emptyList<SaleEntity>()) }; var total by remember { mutableStateOf(0) }; var page by remember { mutableStateOf(0) }
    var debouncedQuery by remember { mutableStateOf("") }; var requestVersion by remember { mutableStateOf(0L) }
    var selectedId by remember { mutableStateOf<Long?>(null) }; var message by remember { mutableStateOf<String?>(null) }
    fun resetResults() { page = 0; requestVersion++ }
    LaunchedEffect(query) { delay(300); debouncedQuery = query; resetResults() }
    LaunchedEffect(debouncedQuery,method,status,page,fromMs,toMs,cashierId,requestVersion) {
        val requestedPage = page; val version = requestVersion
        val filters = SalesRepository.HistoryFilters(fromMs=fromMs,toMs=toMs,cashierUserId=cashierId,method=method,status=status,queryInvoice=debouncedQuery)
        val r = AppGraph.sales.history(filters, requestedPage)
        val current = SalesRepository.HistoryFilters(fromMs=fromMs,toMs=toMs,cashierUserId=cashierId,method=method,status=status,queryInvoice=debouncedQuery)
        if (requestedPage == page && version == requestVersion && filters == current) {
            sales = if (requestedPage == 0) r.first else (sales + r.first).distinctBy { it.id }; total = r.second
        }
    }
    LaunchedEffect(Unit) { cashiers = AppGraph.users.all(); paymentMethods = AppGraph.db.paymentMethodDao().all() }
    Scaffold(topBar={ TopAppBar(title={Text("Transaksi",fontWeight=FontWeight.Bold)}) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            message?.let { TransactionNotice(it) { message=null } }
            OutlinedTextField(query,{query=it},label={Text("Cari invoice")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected=fromMs==null&&toMs==null,onClick={fromMs=null;toMs=null;page=0},label={Text("Semua tanggal")})
                FilterChip(selected=fromMs!=null,onClick={pickFrom=true},label={Text(if(fromMs!=null) "Dari ${Dates.dmy(fromMs!!)}" else "Dari…")})
                FilterChip(selected=toMs!=null,onClick={pickTo=true},label={Text(if(toMs!=null) "Sampai ${Dates.dmy(toMs!!)}" else "Sampai…")})
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=method==null,onClick={method=null;page=0},label={Text("Semua metode")})
                paymentMethods.forEach { payment -> FilterChip(selected=method==payment.type,onClick={method=payment.type;page=0},label={Text(payment.name)}) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(null to "Semua status", "COMPLETED" to "Selesai", "PARTIALLY_REFUNDED" to "Sebagian refund", "REFUNDED" to "Refund").forEach{(id,label)->FilterChip(selected=status==id,onClick={status=id;page=0},label={Text(label)})}
            }
            if (cashiers.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=cashierId==null,onClick={cashierId=null;page=0},label={Text("Semua kasir")})
                    cashiers.forEach { c -> FilterChip(selected=cashierId==c.id,onClick={cashierId=c.id;page=0},label={Text(c.name)}) }
                }
            }
            if(sales.isEmpty()) Text("Belum ada transaksi yang sesuai.",Modifier.padding(16.dp)) else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                items(sales,key={it.id}) { sale -> Card(Modifier.fillMaxWidth().clickable { selectedId=sale.id }) { Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(sale.invoiceNumber,fontWeight=FontWeight.SemiBold); Text("${Dates.dmyhm(sale.createdAt)} • ${sale.userNameSnapshot}",style=MaterialTheme.typography.bodySmall); Text(sale.transactionStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) }; Column(horizontalAlignment=Alignment.End) { Text(Money.fmt(sale.grandTotal),fontWeight=FontWeight.Bold); Text(sale.paymentStatus,style=MaterialTheme.typography.labelSmall) } } } }
                item { if(sales.size<total) TextButton(onClick={page++},modifier=Modifier.fillMaxWidth()){Text("Muat lebih banyak")} }
            }
        }
    }
    selectedId?.let { id -> TransactionDetailDialog(id,user,onDismiss={selectedId=null},onMessage={message=it;selectedId=null;resetResults()},onShare = { sale, items, payments ->
        scope.launch {
            val receipt = ReceiptService(context)
            val file = receipt.createPdf(receiptInfo(AppGraph.store, AppGraph.settings), sale, items, payments)
            receipt.sharePdf(file)
        }
    }, onPrint = { sale, items, payments ->
        scope.launch {
            val address = AppGraph.settings.raw("printer.address", "")
            val info = receiptInfo(AppGraph.store, AppGraph.settings)
            when (val result = BluetoothPrinterService(context).print(address, ReceiptService(context).escPosBytes(info, sale, items, payments))) {
                is BluetoothPrinterService.Result.Success -> message = "Struk berhasil dikirim ke printer"
                is BluetoothPrinterService.Result.Error -> message = "${result.message}. Gunakan Bagikan PDF sebagai fallback."
            }
        }
    }) }
    if (pickFrom) {
        val state = rememberDatePickerState(initialSelectedDateMillis = fromMs)
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = { TextButton(onClick = { fromMs = state.selectedDateMillis?.let { Dates.startOfDay(it) }; page = 0; pickFrom = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("BATAL") } }
        ) { DatePicker(state = state) }
    }
    if (pickTo) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toMs)
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = { TextButton(onClick = { toMs = state.selectedDateMillis?.let { Dates.endOfDay(it) }; page = 0; pickTo = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("BATAL") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun TransactionNotice(message: String, dismiss: () -> Unit) = Card(
    Modifier.fillMaxWidth()
) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f))
        TextButton(onClick = dismiss) { Text("Tutup") }
    }
}

@Composable
private fun TransactionDetailDialog(
    saleId: Long,
    user: UserEntity,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onShare: (SaleEntity, List<SaleItemEntity>, List<PaymentEntity>) -> Unit,
    onPrint: (SaleEntity, List<SaleItemEntity>, List<PaymentEntity>) -> Unit
) {
    var data by remember { mutableStateOf<Triple<SaleEntity, List<SaleItemEntity>, List<PaymentEntity>>?>(null) }
    var refundOpen by remember { mutableStateOf(false) }
    LaunchedEffect(saleId) { data = AppGraph.sales.saleWithDetails(saleId) }
    val detail = data ?: return
    val sale = detail.first
    val items = detail.second
    val payments = detail.third

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detail transaksi") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(sale.invoiceNumber, fontWeight = FontWeight.Bold)
                Text("${Dates.dmyhm(sale.createdAt)} • ${sale.userNameSnapshot}")
                HorizontalDivider()
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(item.productNameSnapshot)
                            Text("${item.quantity} × ${Money.fmt(item.unitPrice)}")
                        }
                        Text(Money.fmt(item.subtotal))
                    }
                }
                HorizontalDivider()
                TotalRow("Subtotal", sale.subtotal)
                if (sale.discount > 0) TotalRow("Diskon", -sale.discount)
                TotalRow("Total", sale.grandTotal)
                payments.forEach { TotalRow(it.method, it.amount) }
                if (sale.changeAmount > 0) TotalRow("Kembalian", sale.changeAmount)
                Text("Status: ${sale.transactionStatus}", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(onClick = { onPrint(sale, items, payments) }) { Text("CETAK ULANG") }
                    TextButton(onClick = { onShare(sale, items, payments) }) { Text("BAGIKAN STRUK") }
                }
                if (user.role == "ADMIN" && (sale.transactionStatus == "COMPLETED" || sale.transactionStatus == "PARTIALLY_REFUNDED")) {
                    TextButton(onClick = { refundOpen = true }) { Text("REFUND") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
    if (refundOpen) {
        RefundDialog(
            sale = sale,
            items = items,
            user = user,
            onDismiss = { refundOpen = false },
            onResult = { message -> refundOpen = false; onMessage(message) }
        )
    }
}

@Composable
private fun RefundDialog(
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
    LaunchedEffect(sale.id) {
        state = AppGraph.refunds.previewState(sale.id)
        quantities = state?.lines?.associate { it.saleItemId to "" }.orEmpty()
        selected = state?.lines?.associate { it.saleItemId to false }.orEmpty()
    }
    val previewState = state
    if (previewState == null) {
        AlertDialog(onDismissRequest=onDismiss,title={Text("Refund ${sale.invoiceNumber}")},text={Text("Memuat data refund…")},confirmButton={},dismissButton={TextButton(onClick=onDismiss){Text("Batal")}})
        return
    }
    val requested = previewState.lines.associate { line ->
        line.saleItemId to if (selected[line.saleItemId] == true) (quantities[line.saleItemId]?.toLongOrNull() ?: 0L) else 0L
    }
    val preview = RefundPreview.preview(previewState.sale.grandTotal, previewState.alreadyRefundedTotal, previewState.lines, requested)
    val valid = reason.isNotBlank() && preview.currentRefundTotal > 0L && OperationalInputRules.validRefundSelection(
        selected,
        requested,
        previewState.lines.associate { it.saleItemId to it.remainingQuantity }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refund ${sale.invoiceNumber}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TotalRow("Total awal", preview.saleGrandTotal)
                TotalRow("Sudah direfund", preview.alreadyRefundedTotal)
                TotalRow("Sisa nilai", preview.remainingSaleValue)
                TotalRow("Refund saat ini", preview.currentRefundTotal)
                previewState.lines.forEach { line ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
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
                            Text(line.productName)
                            Text("Terjual ${line.soldQuantity} • Direfund ${line.alreadyRefundedQuantity} • Sisa ${line.remainingQuantity}", style = MaterialTheme.typography.bodySmall)
                            Text("Sisa nilai net ${Money.fmt(line.remainingRefundableAmount)}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = quantities[line.saleItemId].orEmpty(),
                            onValueChange = { value -> quantities = quantities.toMutableMap().apply { put(line.saleItemId, value.filter(Char::isDigit)) } },
                            label = { Text("Qty") },
                            enabled = !line.fullyRefunded && selected[line.saleItemId] == true,
                            singleLine = true,
                            modifier = Modifier.width(92.dp)
                        )
                    }
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Alasan refund *") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { confirm = true }, enabled = valid) { Text("LANJUTKAN") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
    if (confirm) AlertDialog(
        onDismissRequest={confirm=false}, title={Text("Konfirmasi refund")},
        text={Text("Refund ${Money.fmt(preview.currentRefundTotal)} akan diproses. Tindakan ini tidak dapat dibatalkan.")},
        confirmButton={Button(onClick={
            confirm=false
            val itemById = items.associateBy { it.id }
            val rows = requested.filterValues { it > 0 }.mapNotNull { (id, qty) -> itemById[id]?.let { RefundRepository.RequestedItem(it, qty) } }
            scope.launch { when(val result=AppGraph.refunds.refund(sale.id,user.id,rows,reason)) {
                is RefundRepository.Result.Success -> onResult("Refund ${Money.fmt(result.total)} berhasil; stok dikembalikan")
                is RefundRepository.Result.Error -> error=result.message
            } }
        }){Text("YA, PROSES REFUND")}}, dismissButton={TextButton(onClick={confirm=false}){Text("KEMBALI")}}
    )
}

@Composable
private fun TotalRow(label: String, value: Long) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f))
    Text(Money.fmt(value))
}
