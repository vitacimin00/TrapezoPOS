package com.trapezo.pos.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CustomerEntity
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.PriceEngine
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.data.repository.ShiftRepository
import com.trapezo.pos.domain.model.CartEngine
import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.DiscountKind
import com.trapezo.pos.domain.model.OrderDiscount
import com.trapezo.pos.domain.model.PayMethod
import com.trapezo.pos.domain.model.PaymentAllocation
import com.trapezo.pos.printer.ReceiptService
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.printer.receiptInfo
import com.trapezo.pos.scanner.BarcodeScannerScreen
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(user: UserEntity) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var shift by remember { mutableStateOf<ShiftEntity?>(null) }
    var search by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<com.trapezo.pos.data.entity.ProductEntity>()) }
    var cart by remember { mutableStateOf(emptyList<CartLine>()) }
    var discount by remember { mutableStateOf(OrderDiscount()) }
    var customer by remember { mutableStateOf<CustomerEntity?>(null) }
    var taxPct by remember { mutableStateOf(0L) }
    var svcPct by remember { mutableStateOf(0L) }
    var rounding by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf<String?>(null) }
    var nextInvoice by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var openShiftDialog by remember { mutableStateOf(false) }
    var closeShiftDialog by remember { mutableStateOf(false) }
    var cashMovementType by remember { mutableStateOf<String?>(null) }
    var discountOpen by remember { mutableStateOf(false) }
    var customersOpen by remember { mutableStateOf(false) }
    var paymentOpen by remember { mutableStateOf(false) }
    var quantityTarget by remember { mutableStateOf<CartLine?>(null) }
    var successReceipt by remember { mutableStateOf<ReceiptPayload?>(null) }
    var paymentMethods by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun reloadShift() {
        scope.launch { shift = AppGraph.db.shiftDao().openShiftForUser(user.id) }
    }

    fun addProduct(product: com.trapezo.pos.data.entity.ProductEntity) {
        val line = CartLine(
            productId = product.id,
            name = product.name,
            barcode = product.barcode.ifBlank { null },
            unitPrice = product.posSellPrice.takeIf { it > 0 } ?: product.sellPrice,
            quantity = 1,
            trackInventory = product.trackInventory,
            stockQty = product.stockQty,
            taxFreeItem = product.taxFreeItem,
            nonServiceChargeItem = product.nonServiceCharge
        )
        val result = CartEngine.add(cart, line)
        cart = result.lines
        if (!result.accepted) notice = result.message
    }
    fun acceptBarcode(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        scope.launch {
            val product = AppGraph.products.byBarcode(clean) ?: AppGraph.products.barcodeInExtraTable(clean)
            if (product == null) notice = "Produk dengan barcode/SKU '$clean' tidak ditemukan"
            else { addProduct(product); search = "" }
        }
    }

    val totals = remember(cart, discount, taxPct, svcPct, rounding) {
        PriceEngine.totals(cart, discount, taxPct, svcPct, rounding)
    }

    LaunchedEffect(Unit) {
        reloadShift()
        taxPct = AppGraph.settings.taxPercent()
        svcPct = AppGraph.settings.servicePercent()
        rounding = AppGraph.settings.rounding()
        nextInvoice = AppGraph.settings.peekInvoiceNumber()
        paymentMethods = AppGraph.db.paymentMethodDao().active().map { it.type to it.name }
    }
    LaunchedEffect(search) {
        delay(300)
        val requested = search
        val result = AppGraph.products.posSearch(requested, limit = 60)
        if (search == requested) found = result
    }

    if (scannerOpen) {
        BarcodeScannerScreen(onBarcode = { code -> scannerOpen = false; acceptBarcode(code) }, onDismiss = { scannerOpen = false })
        return
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(user.name, fontWeight = FontWeight.Bold)
                    Text("No. transaksi: $nextInvoice", style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (shift == null) "Shift belum dibuka" else "Shift #${shift!!.id} aktif",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (shift == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            },
            actions = {
                TextButton(onClick = { customersOpen = true }) { Icon(Icons.Default.People, null); Text(customer?.name ?: " Customer") }
                if (shift == null) TextButton(onClick = { openShiftDialog = true }) { Text("BUKA SHIFT") }
                else TextButton(onClick = { closeShiftDialog = true }) { Text("TUTUP SHIFT") }
            }
        )
    }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notice?.let { NoticeCard(it) { notice = null } }
            shift?.let { active -> ShiftStatusCard(active, onCashIn = { cashMovementType = "CASH_IN" }, onCashOut = { cashMovementType = "CASH_OUT" }) }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Cari nama, SKU, atau barcode") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { acceptBarcode(search) }),
                trailingIcon = { TextButton(onClick = { scannerOpen = true }) { Text("SCAN") } }
            )
            if (shift == null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Buka shift sebelum transaksi", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Produk tetap dapat dicari, tetapi pembayaran dikunci sampai shift dibuka.", color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { openShiftDialog = true }, Modifier.padding(top = 8.dp)) { Text("BUKA SHIFT") }
                    }
                }
            }
            ProductCandidates(found, onAdd = ::addProduct)
            HorizontalDivider()
            CartSection(cart = cart, totals = totals, discount = discount, onQuantity = { target -> quantityTarget = target }, onRemove = { id -> cart = CartEngine.remove(cart, id).lines }, onDiscount = { discountOpen = true }, onPay = { paymentOpen = true }, payEnabled = cart.isNotEmpty() && shift != null)
        }
    }

    if (openShiftDialog) OpenShiftDialog(user, onDismiss = { openShiftDialog = false }, onResult = { message -> openShiftDialog = false; notice = message; reloadShift() })
    if (closeShiftDialog && shift != null) CloseShiftDialog(shift!!, user, onDismiss = { closeShiftDialog = false }, onResult = { message -> closeShiftDialog = false; notice = message; reloadShift() })
    cashMovementType?.let { type -> if (shift != null) CashMovementDialog(shift!!, type, user, onDismiss = { cashMovementType = null }, onResult = { msg -> cashMovementType = null; notice = msg; reloadShift() }) }
    if (discountOpen) DiscountDialog(discount, totals.subtotal, onDismiss = { discountOpen = false }, onApply = { discount = it; discountOpen = false })
    if (customersOpen) CustomerPickerDialog(onDismiss = { customersOpen = false }, onSelect = { customer = it; customersOpen = false })
    quantityTarget?.let { target -> QuantityDialog(target, onDismiss = { quantityTarget = null }, onSet = { qty ->
        val result = CartEngine.setQuantity(cart, target.productId, qty)
        cart = result.lines; if (!result.accepted) notice = result.message; quantityTarget = null
    }) }
    if (paymentOpen && shift != null) PaymentDialog(
        total = totals.grandTotal,
        methods = paymentMethods,
        onDismiss = { paymentOpen = false },
        onComplete = { tenders, refs ->
            scope.launch {
                when (val result = AppGraph.sales.checkout(cart, discount, tenders, refs, user, shift!!.id, customer?.id, customer?.name)) {
                    is SalesRepository.CheckoutResult.Success -> {
                        val details = AppGraph.sales.saleWithDetails(result.sale.id)
                        if (details != null) successReceipt = ReceiptPayload(details.first, details.second, details.third)
                        cart = emptyList(); discount = OrderDiscount(); customer = null; paymentOpen = false
                        nextInvoice = AppGraph.settings.peekInvoiceNumber()
                        reloadShift()
                    }
                    is SalesRepository.CheckoutResult.Failure -> notice = result.error
                }
            }
        }
    )
    successReceipt?.let { payload ->
        ReceiptSuccessDialog(
            payload = payload,
            onDismiss = { successReceipt = null },
            onShare = {
                scope.launch {
                    val receipt = ReceiptService(context)
                    val file = receipt.createPdf(receiptInfo(AppGraph.store, AppGraph.settings), payload.sale, payload.items, payload.payments)
                    receipt.sharePdf(file)
                }
            },
            onPrint = {
                scope.launch {
                    val info = receiptInfo(AppGraph.store, AppGraph.settings)
                    val address = AppGraph.settings.raw("printer.address", "")
                    when (val result = BluetoothPrinterService(context).print(address, ReceiptService(context).escPosBytes(info, payload.sale, payload.items, payload.payments))) {
                        is BluetoothPrinterService.Result.Success -> notice = "Struk berhasil dikirim ke printer"
                        is BluetoothPrinterService.Result.Error -> notice = "${result.message}. Gunakan Bagikan PDF sebagai fallback."
                    }
                }
            }
        )
    }
}

private data class ReceiptPayload(val sale: SaleEntity, val items: List<SaleItemEntity>, val payments: List<PaymentEntity>)

@Composable private fun NoticeCard(message: String, dismiss: () -> Unit) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer); TextButton(onClick = dismiss) { Text("Tutup") } } }

@Composable private fun ShiftStatusCard(shift: ShiftEntity, onCashIn: () -> Unit, onCashOut: () -> Unit) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Shift aktif • ${shift.userNameSnapshot}", fontWeight = FontWeight.Bold); Text("Modal awal ${Money.fmt(shift.openingCash)} • Kas seharusnya ${Money.fmt(shift.expectedCash)}", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onCashIn) { Text("Cash In") }; TextButton(onClick = onCashOut) { Text("Cash Out") } } }

@Composable
private fun ProductCandidates(products: List<com.trapezo.pos.data.entity.ProductEntity>, onAdd: (com.trapezo.pos.data.entity.ProductEntity) -> Unit) {
    if (products.isEmpty()) return
    LazyColumn(Modifier.height(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(products.take(20), key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().clickable { onAdd(p) }) { Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.sku.ifBlank { p.barcode.ifBlank { "Tanpa SKU" } }} • Stok ${p.stockQty}", style = MaterialTheme.typography.bodySmall) }
                Text(Money.fmt(p.posSellPrice.takeIf { it > 0 } ?: p.sellPrice), color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.Add, "Tambah", Modifier.padding(start = 8.dp))
            } }
        }
    }
}

@Composable
private fun CartSection(cart: List<CartLine>, totals: com.trapezo.pos.domain.model.Totals, discount: OrderDiscount, onQuantity: (CartLine) -> Unit, onRemove: (Long) -> Unit, onDiscount: () -> Unit, onPay: () -> Unit, payEnabled: Boolean) {
    // Animated grand total counter — smooth feedback when the cart changes.
    val animatedTotal by animateFloatAsState(
        targetValue = totals.grandTotal.toFloat(),
        animationSpec = tween(durationMillis = 250),
        label = "grandTotal"
    )
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Keranjang (${cart.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onDiscount) { Text(if (discount.kind == DiscountKind.NONE) "Diskon" else "Diskon aktif") } }
        if (cart.isEmpty()) Text("Keranjang masih kosong.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.height(180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cart, key = { it.productId }) { line ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onQuantity(line) }) { Text(line.name, fontWeight = FontWeight.SemiBold); Text("${Money.fmt(line.unitPrice)} × ${line.quantity} = ${Money.fmt(line.subtotal)}", color = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onQuantity(line.copy(quantity = line.quantity - 1)) }) { Icon(Icons.Default.Remove, "Kurangi") }
                    Text(line.quantity.toString(), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { onQuantity(line.copy(quantity = line.quantity + 1)) }) { Icon(Icons.Default.Add, "Tambah") }
                    IconButton(onClick = { onRemove(line.productId) }) { Icon(Icons.Default.Delete, "Hapus") }
                } }
            }
        }
        HorizontalDivider()
        TotalRow("Subtotal", totals.subtotal); if (totals.discount > 0) TotalRow("Diskon", -totals.discount); if (totals.tax > 0) TotalRow("Pajak", totals.tax); if (totals.serviceCharge > 0) TotalRow("Service", totals.serviceCharge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("GRAND TOTAL", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(Money.fmt(animatedTotal.toLong()), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        Button(onClick = onPay, enabled = payEnabled, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (payEnabled) "BAYAR" else "BUKA SHIFT UNTUK BAYAR", fontWeight = FontWeight.Bold) }
    }
}
@Composable private fun TotalRow(label: String, value: Long) = Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Text(Money.fmt(value)) }

@Composable
private fun OpenShiftDialog(user: UserEntity, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var opening by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Buka shift") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Kasir: ${user.name}"); OutlinedTextField(opening, { opening = it }, label = { Text("Modal awal") }, singleLine = true); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { scope.launch { when (val r = AppGraph.shifts.open(user, Money.parse(opening))) { is ShiftRepository.Result.Ok -> onResult("Shift berhasil dibuka"); is ShiftRepository.Result.Error -> error = r.message } } }) { Text("BUKA SHIFT") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun CloseShiftDialog(shift: ShiftEntity, user: UserEntity, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var actual by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    val expected = shift.openingCash + shift.totalCashSales + shift.cashIn - shift.cashOut
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tutup shift") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { TotalRow("Modal awal", shift.openingCash); TotalRow("Penjualan tunai", shift.totalCashSales); TotalRow("Cash in", shift.cashIn); TotalRow("Cash out", -shift.cashOut); HorizontalDivider(); TotalRow("Kas seharusnya", expected); OutlinedTextField(actual, { actual = it }, label = { Text("Kas aktual") }, singleLine = true); Money.parse(actual).takeIf { actual.isNotBlank() }?.let { TotalRow("Selisih", it - expected) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { scope.launch { when (val r = AppGraph.shifts.close(shift, Money.parse(actual), user.id)) { is ShiftRepository.Result.Ok -> onResult("Shift ditutup. Selisih ${Money.fmt(r.shift.difference)}"); is ShiftRepository.Result.Error -> error = r.message } } }) { Text("TUTUP SHIFT") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun CashMovementDialog(shift: ShiftEntity, type: String, user: UserEntity, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var amount by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope(); val label = if (type == "CASH_IN") "Cash In" else "Cash Out"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(label) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(amount, { amount = it }, label = { Text("Nominal") }, singleLine = true); OutlinedTextField(note, { note = it }, label = { Text("Catatan") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { scope.launch { when (val r = AppGraph.shifts.cash(shift, type, Money.parse(amount), note, user.id)) { is ShiftRepository.Result.Ok -> onResult("$label tersimpan"); is ShiftRepository.Result.Error -> error = r.message } } }) { Text("SIMPAN") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun QuantityDialog(line: CartLine, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var value by remember(line.productId, line.quantity) { mutableStateOf(line.quantity.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ubah quantity") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(line.name); OutlinedTextField(value, { value = it }, label = { Text("Quantity (0 untuk hapus)") }, singleLine = true); if (line.trackInventory) Text("Stok tersedia: ${line.stockQty}") } }, confirmButton = { Button(onClick = { onSet(value.toLongOrNull() ?: line.quantity) }) { Text("TERAPKAN") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun DiscountDialog(current: OrderDiscount, subtotal: Long, onDismiss: () -> Unit, onApply: (OrderDiscount) -> Unit) {
    var kind by remember { mutableStateOf(current.kind) }; var value by remember { mutableStateOf(if (current.kind == DiscountKind.NONE) "" else current.value.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Diskon transaksi") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(DiscountKind.NONE to "Tanpa diskon", DiscountKind.NOMINAL to "Nominal (Rp)", DiscountKind.PERCENT to "Persentase (%)").forEach { (id,label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == id, { kind = id }); Text(label) } }
        if (kind != DiscountKind.NONE) OutlinedTextField(value, { value = it }, label = { Text(if (kind == DiscountKind.PERCENT) "Persen" else "Nominal") }, singleLine = true)
        val proposed = OrderDiscount(kind, Money.parse(value)).amountFor(subtotal); Text("Diskon: ${Money.fmt(proposed)}")
    } }, confirmButton = { Button(onClick = { onApply(OrderDiscount(kind, Money.parse(value))) }) { Text("TERAPKAN") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun CustomerPickerDialog(onDismiss: () -> Unit, onSelect: (CustomerEntity?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var customers by remember { mutableStateOf(emptyList<CustomerEntity>()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    LaunchedEffect(query, page) {
        if (page == 0) delay(300)
        val requestedQuery = query
        val requestedPage = page
        val result = AppGraph.customers.page(requestedQuery, requestedPage, 50)
        if (query == requestedQuery && page == requestedPage) {
            total = result.second
            customers = if (requestedPage == 0) result.first
            else (customers + result.first).distinctBy { it.id }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pilih customer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(query, { query = it; page = 0 }, label = { Text("Cari nama / HP / kode") }, singleLine = true)
            TextButton(onClick = { onSelect(null) }) { Text("Tanpa customer") }
            LazyColumn(Modifier.height(180.dp)) {
                items(customers, key = { it.id }) { customer ->
                    TextButton(onClick = { onSelect(customer) }, Modifier.fillMaxWidth()) {
                        Column { Text(customer.name); Text("${customer.code} • ${customer.phone}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (customers.size < total) item {
                    TextButton(onClick = { page += 1 }, Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun PaymentDialog(total: Long, methods: List<Pair<String, String>>, onDismiss: () -> Unit, onComplete: (Map<String, Long>, Map<String, String>) -> Unit) {
    // All method state is plain String/label data — no sealed-class objects are captured
    // inside the composable lambdas, which avoids the recomposition NPE seen previously.
    fun labelOf(id: String): String = methods.firstOrNull { it.first == id }?.second ?: id
    var methodId by remember(methods) { mutableStateOf(methods.firstOrNull()?.first ?: "") }
    var amount by remember { mutableStateOf(total.toString()) }
    var reference by remember { mutableStateOf("") }
    var tenders by remember { mutableStateOf(linkedMapOf<String, Long>()) }
    var references by remember { mutableStateOf(linkedMapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val draft = PaymentAllocation.settle(tenders, total)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pembayaran • ${Money.fmt(total)}") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (methods.isEmpty()) "Tidak ada metode pembayaran aktif" else "Pilih metode")
        Column(Modifier.height(102.dp).verticalScroll(rememberScrollState())) { methods.forEach { (id, label) -> Row(Modifier.fillMaxWidth().clickable { methodId = id; amount = (total - draft.settled.values.sum()).coerceAtLeast(0).toString() }, verticalAlignment = Alignment.CenterVertically) { RadioButton(methodId == id, { methodId = id }); Text(label) } } }
        if (methodId == "QRIS") Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text("QRIS manual: pastikan pembayaran diterima sebelum konfirmasi. Integrasi API QRIS dapat ditambahkan melalui SyncService di masa depan.", Modifier.padding(10.dp)) }
        OutlinedTextField(amount, { amount = it }, label = { Text("Nominal ${labelOf(methodId)}") }, singleLine = true)
        if (methodId != "CASH") OutlinedTextField(reference, { reference = it }, label = { Text("Nomor referensi (opsional)") }, singleLine = true)
        if (methodId == "CASH") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10_000L,20_000L,50_000L,100_000L).forEach { n -> AssistChip(onClick = { amount = n.toString() }, label = { Text(Money.fmt(n)) }) }; AssistChip(onClick = { amount = total.toString() }, label = { Text("Uang pas") }) }
        OutlinedButton(onClick = { val n = Money.parse(amount); if (methodId.isBlank()) error = "Aktifkan metode pembayaran terlebih dahulu" else if (n <= 0) error = "Nominal harus lebih besar dari 0" else { val copy = LinkedHashMap(tenders); copy[methodId] = (copy[methodId] ?: 0L) + n; val candidate = PaymentAllocation.settle(copy, total); if (methodId != PaymentAllocation.CASH && candidate.settled.isEmpty()) { error = "Pembayaran non-tunai tidak boleh melebihi sisa tagihan" } else { tenders = copy; val refs = LinkedHashMap(references); if(reference.isNotBlank()) refs[methodId] = reference; references = refs; amount = (total - candidate.settled.values.sum()).coerceAtLeast(0).toString(); reference = ""; error = null } } }, Modifier.fillMaxWidth(), enabled = methods.isNotEmpty()) { Text("TAMBAH PEMBAYARAN") }
        if (tenders.isNotEmpty()) { Text("Pembayaran diterima", fontWeight = FontWeight.SemiBold); tenders.forEach { (m, n) -> Row(Modifier.fillMaxWidth()) { Text(m, Modifier.weight(1f)); Text(Money.fmt(n)); IconButton(onClick = { val c = LinkedHashMap(tenders); c.remove(m); tenders = c }) { Icon(Icons.Default.Close, "Hapus") } } } }
        val settled = PaymentAllocation.settle(tenders, total); TotalRow("Total tender", settled.tendered); if (settled.change > 0) TotalRow("Kembalian", settled.change); if (settled.shortfall > 0) Text("Kurang ${Money.fmt(settled.shortfall)}", color = MaterialTheme.colorScheme.error); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { Button(onClick = { val settled = PaymentAllocation.settle(tenders, total); if(settled.shortfall > 0 || settled.settled.values.sum() != total) error = "Pembayaran belum valid atau belum mencukupi" else onComplete(tenders, references) }, enabled = methods.isNotEmpty()) { Text("KONFIRMASI BAYAR") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("BATAL") } })
}

@Composable
private fun ReceiptSuccessDialog(
    payload: ReceiptPayload,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Pembayaran berhasil") },
    text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Invoice ${payload.sale.invoiceNumber}", fontWeight = FontWeight.Bold)
            TotalRow("Total", payload.sale.grandTotal)
            TotalRow("Dibayar", payload.sale.paidAmount)
            if (payload.sale.changeAmount > 0) TotalRow("Kembalian", payload.sale.changeAmount)
            Text("Transaksi, stok, pembayaran, shift, dan audit log sudah disimpan secara atomik.", style = MaterialTheme.typography.bodySmall)
        }
    },
    confirmButton = {
        Row {
            TextButton(onClick = onPrint) { Text("CETAK") }
            Button(onClick = onShare) { Text("BAGIKAN PDF") }
        }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("TRANSAKSI BARU") } }
)
