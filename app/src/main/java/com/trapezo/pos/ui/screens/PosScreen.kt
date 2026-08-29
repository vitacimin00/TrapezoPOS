package com.trapezo.pos.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Discount
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.CustomerEntity
import com.trapezo.pos.data.entity.PaymentEntity
import com.trapezo.pos.data.entity.ProductEntity
import com.trapezo.pos.data.entity.SaleEntity
import com.trapezo.pos.data.entity.SaleItemEntity
import com.trapezo.pos.data.entity.ShiftEntity
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.PriceEngine
import com.trapezo.pos.data.repository.SalesRepository
import com.trapezo.pos.domain.model.CartEngine
import com.trapezo.pos.domain.model.CartLine
import com.trapezo.pos.domain.model.DiscountKind
import com.trapezo.pos.domain.model.OrderDiscount
import com.trapezo.pos.domain.model.PaymentAllocation
import com.trapezo.pos.domain.model.Totals
import com.trapezo.pos.printer.BluetoothPrinterService
import com.trapezo.pos.printer.ReceiptService
import com.trapezo.pos.printer.receiptInfo
import com.trapezo.pos.scanner.BarcodeScannerScreen
import com.trapezo.pos.ui.components.AmountRow
import com.trapezo.pos.ui.components.EmptyState
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.LoadingState
import com.trapezo.pos.ui.components.MoneyText
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
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounded POS search contract shared with the repository query limit. */
private const val POS_SEARCH_LIMIT = 20

private data class ReceiptPayload(
    val sale: SaleEntity,
    val items: List<SaleItemEntity>,
    val payments: List<PaymentEntity>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(user: UserEntity) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val feedback = rememberFeedback()
    val widthClass = currentWidthClass

    var shift by remember { mutableStateOf<ShiftEntity?>(null) }
    var shiftLoaded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<ProductEntity>()) }
    var searching by remember { mutableStateOf(false) }
    var cart by remember { mutableStateOf(emptyList<CartLine>()) }
    var discount by remember { mutableStateOf(OrderDiscount()) }
    var customer by remember { mutableStateOf<CustomerEntity?>(null) }
    var taxPct by remember { mutableStateOf(0L) }
    var svcPct by remember { mutableStateOf(0L) }
    var rounding by remember { mutableStateOf(0L) }
    var nextInvoice by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }
    var discountOpen by remember { mutableStateOf(false) }
    var customersOpen by remember { mutableStateOf(false) }
    var paymentOpen by remember { mutableStateOf(false) }
    var cartSheetOpen by remember { mutableStateOf(false) }
    var successReceipt by remember { mutableStateOf<ReceiptPayload?>(null) }
    var paymentMethods by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun reloadShift() {
        scope.launch {
            shift = AppGraph.db.shiftDao().openShiftForUser(user.id)
            shiftLoaded = true
        }
    }

    fun addProduct(product: ProductEntity) {
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
        if (!result.accepted) feedback?.warning(result.message ?: "Item tidak dapat ditambahkan")
    }

    fun changeQuantity(line: CartLine, quantity: Long) {
        val result = CartEngine.setQuantity(cart, line.productId, quantity)
        cart = result.lines
        if (!result.accepted) feedback?.warning(result.message ?: "Quantity tidak valid")
    }

    fun acceptBarcode(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        scope.launch {
            val product = AppGraph.products.byBarcode(clean) ?: AppGraph.products.barcodeInExtraTable(clean)
            if (product == null) feedback?.error("Produk dengan barcode/SKU \"$clean\" tidak ditemukan")
            else {
                addProduct(product)
                search = ""
            }
        }
    }

    // Financial totals always come from the locked PricingEngine path — never recomputed in UI.
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
        searching = true
        delay(300)
        val requested = search
        val result = AppGraph.products.posSearch(requested, limit = POS_SEARCH_LIMIT)
        if (search == requested) {
            found = result
            searching = false
        }
    }

    if (scannerOpen) {
        BarcodeScannerScreen(
            onBarcode = { code -> scannerOpen = false; acceptBarcode(code) },
            onDismiss = { scannerOpen = false }
        )
        return
    }

    fun completeCheckout(tenders: Map<String, Long>, refs: Map<String, String>) {
        val activeShift = shift ?: return
        scope.launch {
            when (val result = AppGraph.sales.checkout(cart, discount, tenders, refs, user, activeShift.id, customer?.id, customer?.name)) {
                is SalesRepository.CheckoutResult.Success -> {
                    val details = AppGraph.sales.saleWithDetails(result.sale.id)
                    if (details != null) successReceipt = ReceiptPayload(details.first, details.second, details.third)
                    cart = emptyList()
                    discount = OrderDiscount()
                    customer = null
                    paymentOpen = false
                    cartSheetOpen = false
                    nextInvoice = AppGraph.settings.peekInvoiceNumber()
                    reloadShift()
                }
                is SalesRepository.CheckoutResult.Failure -> feedback?.error(result.error)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PosHeader(
            user = user,
            shift = shift,
            invoice = nextInvoice,
            customer = customer,
            onCustomer = { customersOpen = true },
            onClearCustomer = { customer = null }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (widthClass.isExpanded) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.62f).fillMaxHeight()) {
                    ProductBrowser(
                        search = search,
                        onSearch = { search = it },
                        onSubmit = { acceptBarcode(search) },
                        onScan = { scannerOpen = true },
                        products = found,
                        searching = searching,
                        shiftOpen = shift != null,
                        onAdd = ::addProduct
                    )
                }
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(0.38f).fillMaxHeight()) {
                    CartPane(
                        cart = cart,
                        totals = totals,
                        discount = discount,
                        shiftOpen = shift != null,
                        shiftLoaded = shiftLoaded,
                        onQuantity = ::changeQuantity,
                        onRemove = { id -> cart = CartEngine.remove(cart, id).lines },
                        onDiscount = { discountOpen = true },
                        onPay = { paymentOpen = true }
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                ProductBrowser(
                    search = search,
                    onSearch = { search = it },
                    onSubmit = { acceptBarcode(search) },
                    onScan = { scannerOpen = true },
                    products = found,
                    searching = searching,
                    shiftOpen = shift != null,
                    onAdd = ::addProduct,
                    bottomInset = if (cart.isEmpty()) Space.sm else 84.dp
                )
                if (cart.isNotEmpty()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        CompactCartBar(
                            itemCount = cart.sumOf { it.quantity },
                            total = totals.grandTotal,
                            onOpen = { cartSheetOpen = true }
                        )
                    }
                }
            }
        }
    }

    if (cartSheetOpen && !widthClass.isExpanded) {
        ModalBottomSheet(
            onDismissRequest = { cartSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(Modifier.fillMaxHeight(0.9f)) {
                CartPane(
                    cart = cart,
                    totals = totals,
                    discount = discount,
                    shiftOpen = shift != null,
                    shiftLoaded = shiftLoaded,
                    onQuantity = ::changeQuantity,
                    onRemove = { id ->
                        cart = CartEngine.remove(cart, id).lines
                        if (cart.isEmpty()) cartSheetOpen = false
                    },
                    onDiscount = { discountOpen = true },
                    onPay = { paymentOpen = true }
                )
            }
        }
    }

    if (discountOpen) {
        DiscountSheet(
            current = discount,
            subtotal = totals.subtotal,
            onDismiss = { discountOpen = false },
            onApply = { discount = it; discountOpen = false }
        )
    }
    if (customersOpen) {
        CustomerPickerSheet(
            onDismiss = { customersOpen = false },
            onSelect = { customer = it; customersOpen = false }
        )
    }
    if (paymentOpen && shift != null) {
        PaymentSheet(
            total = totals.grandTotal,
            methods = paymentMethods,
            onDismiss = { paymentOpen = false },
            onComplete = ::completeCheckout
        )
    }
    successReceipt?.let { payload ->
        CheckoutSuccessSheet(
            payload = payload,
            onNewTransaction = { successReceipt = null },
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
                    when (val result = BluetoothPrinterService(context).print(
                        address,
                        ReceiptService(context).escPosBytes(info, payload.sale, payload.items, payload.payments)
                    )) {
                        is BluetoothPrinterService.Result.Success -> feedback?.success("Struk dikirim ke printer")
                        is BluetoothPrinterService.Result.Error ->
                            feedback?.error("${result.message}. Gunakan Bagikan PDF sebagai alternatif.")
                    }
                }
            }
        )
    }
}

/** Functional POS header: identity, shift state, next invoice, customer context. */
@Composable
private fun PosHeader(
    user: UserEntity,
    shift: ShiftEntity?,
    invoice: String,
    customer: CustomerEntity?,
    onCustomer: () -> Unit,
    onClearCustomer: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        Column(Modifier.weight(1f)) {
            Text("Kasir", style = MaterialTheme.typography.titleLarge)
            Text(
                "${user.name} • ${if (invoice.isBlank()) "menyiapkan nomor" else invoice}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (shift == null) {
            StatusBadge("Shift belum dibuka", Tone.WARNING, Icons.Default.Schedule)
        } else {
            StatusBadge("Shift aktif", Tone.SUCCESS, Icons.Default.Schedule)
        }
        if (customer == null) {
            OutlinedButton(onClick = onCustomer, shape = Radius.field, modifier = Modifier.heightIn(min = Touch.control)) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Customer")
            }
        } else {
            AssistChip(
                onClick = onClearCustomer,
                label = { Text(customer.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Hapus customer", modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

/** Product browser pane: dominant search, then results with clear stock semantics. */
@Composable
private fun ProductBrowser(
    search: String,
    onSearch: (String) -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
    products: List<ProductEntity>,
    searching: Boolean,
    shiftOpen: Boolean,
    onAdd: (ProductEntity) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = Space.sm
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            SearchField(
                value = search,
                onValueChange = onSearch,
                placeholder = "Cari nama, SKU, atau barcode",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                trailing = {
                    IconButton(onClick = onScan, modifier = Modifier.size(Touch.control)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode")
                    }
                }
            )
            if (!shiftOpen) {
                Surface(
                    color = TrapezoStatus.warningContainer,
                    shape = Radius.card,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = TrapezoStatus.warning, modifier = Modifier.size(18.dp))
                        Text(
                            "Buka shift di menu Shift untuk mulai menerima pembayaran.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TrapezoStatus.warning
                        )
                    }
                }
            }
        }
        when {
            products.isEmpty() && search.isBlank() -> EmptyState(
                title = "Mulai transaksi",
                message = "Cari produk berdasarkan nama, SKU, atau barcode. Anda juga dapat memakai tombol scan.",
                icon = Icons.Default.QrCodeScanner
            )
            products.isEmpty() && searching -> LoadingState("Mencari produk…")
            products.isEmpty() -> EmptyState(
                title = "Tidak ada hasil",
                message = "Tidak ada produk cocok untuk \"$search\". Periksa ejaan atau gunakan SKU/barcode.",
                icon = Icons.Default.Inventory2
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.lg, end = Space.lg, bottom = bottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(products, key = { it.id }) { product -> ProductPickRow(product, onAdd) }
                if (products.size == POS_SEARCH_LIMIT) {
                    item {
                        Text(
                            "Menampilkan $POS_SEARCH_LIMIT hasil teratas. Persempit pencarian untuk hasil lebih spesifik.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Space.sm)
                        )
                    }
                }
            }
        }
    }
}

/** POS product row: only the fields an operator needs at the counter. */
@Composable
private fun ProductPickRow(product: ProductEntity, onAdd: (ProductEntity) -> Unit) {
    val outOfStock = product.trackInventory && product.stockQty <= 0
    val lowStock = product.trackInventory && !outOfStock && product.stockQty <= product.lowStockAlert
    val price = product.posSellPrice.takeIf { it > 0 } ?: product.sellPrice
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = Radius.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = !outOfStock) { onAdd(product) }
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    product.sku.ifBlank { product.barcode.ifBlank { "Tanpa SKU" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when {
                    outOfStock -> StatusBadge("Stok habis", Tone.DANGER)
                    lowStock -> StatusBadge("Sisa ${product.stockQty} ${product.uom}", Tone.WARNING)
                    product.trackInventory -> Text(
                        "Stok ${product.stockQty} ${product.uom}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MoneyText(
                amount = price,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                weight = FontWeight.SemiBold
            )
            FilledTonalButton(
                onClick = { onAdd(product) },
                enabled = !outOfStock,
                shape = Radius.control,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.md),
                modifier = Modifier.heightIn(min = Touch.control)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Tambah ${product.name}", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Cart pane with compact rows, dominant total, and a single primary action. */
@Composable
private fun CartPane(
    cart: List<CartLine>,
    totals: Totals,
    discount: OrderDiscount,
    shiftOpen: Boolean,
    shiftLoaded: Boolean,
    onQuantity: (CartLine, Long) -> Unit,
    onRemove: (Long) -> Unit,
    onDiscount: () -> Unit,
    onPay: () -> Unit
) {
    val animatedTotal by animateFloatAsState(
        targetValue = totals.grandTotal.toFloat(),
        animationSpec = tween(220),
        label = "grandTotal"
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Keranjang", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDiscount) {
                Icon(Icons.Default.Discount, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Space.xs))
                Text(if (discount.kind == DiscountKind.NONE) "Diskon" else "Diskon aktif")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (cart.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyState(
                    title = "Keranjang kosong",
                    message = "Pilih produk dari daftar untuk menambahkannya ke transaksi ini.",
                    icon = Icons.Default.ShoppingCartCheckout
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                items(cart, key = { it.productId }) { line ->
                    CartRow(
                        line = line,
                        onDecrease = { onQuantity(line, line.quantity - 1) },
                        onIncrease = { onQuantity(line, line.quantity + 1) },
                        onRemove = { onRemove(line.productId) }
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .padding(horizontal = Space.lg, vertical = Space.md)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.xs)
        ) {
            AmountRow("Subtotal", totals.subtotal)
            if (totals.discount > 0) AmountRow("Diskon", -totals.discount, tone = Tone.WARNING)
            if (totals.tax > 0) AmountRow("Pajak", totals.tax)
            if (totals.serviceCharge > 0) AmountRow("Service charge", totals.serviceCharge)
            HorizontalDivider(Modifier.padding(vertical = Space.xs), color = MaterialTheme.colorScheme.outlineVariant)
            AmountRow("TOTAL", animatedTotal.toLong(), emphasize = true)
            Spacer(Modifier.height(Space.xs))
            Button(
                onClick = onPay,
                enabled = cart.isNotEmpty() && shiftOpen,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) {
                Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.sm))
                Text(
                    when {
                        !shiftLoaded -> "Memuat shift…"
                        !shiftOpen -> "Buka shift untuk membayar"
                        cart.isEmpty() -> "Keranjang kosong"
                        else -> "BAYAR • ${Money.fmt(totals.grandTotal)}"
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Compact cart line: qty stepper is directly usable without opening a dialog. */
@Composable
private fun CartRow(
    line: CartLine,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = Radius.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        line.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${Money.fmt(line.unitPrice)} × ${line.quantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MoneyText(line.subtotal, weight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = Radius.control
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDecrease, modifier = Modifier.size(Touch.control)) {
                            Icon(Icons.Default.Remove, contentDescription = "Kurangi ${line.name}")
                        }
                        Text(
                            line.quantity.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(36.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        IconButton(onClick = onIncrease, modifier = Modifier.size(Touch.control)) {
                            Icon(Icons.Default.Add, contentDescription = "Tambah ${line.name}")
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(Touch.control)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Hapus ${line.name} dari keranjang",
                        tint = TrapezoStatus.danger
                    )
                }
            }
        }
    }
}

/** Phone-portrait sticky cart summary; tapping opens the cart sheet. */
@Composable
private fun CompactCartBar(itemCount: Long, total: Long, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            Modifier
                .padding(horizontal = Space.lg, vertical = Space.md)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("$itemCount item", style = MaterialTheme.typography.labelMedium)
                Text(Money.fmt(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIHAT KERANJANG", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(Space.xs))
                Icon(Icons.Default.ShoppingCartCheckout, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscountSheet(
    current: OrderDiscount,
    subtotal: Long,
    onDismiss: () -> Unit,
    onApply: (OrderDiscount) -> Unit
) {
    var kind by remember { mutableStateOf(current.kind) }
    var value by remember { mutableStateOf(if (current.kind == DiscountKind.NONE) "" else current.value.toString()) }
    val maxDiscount = if (kind == DiscountKind.PERCENT) 100L else Money.MAX_RUPIAH
    val parsed = Money.parseOrNull(value, max = maxDiscount, allowBlank = kind == DiscountKind.NONE)
    val invalid = kind != DiscountKind.NONE && value.isNotBlank() && parsed == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Diskon Transaksi", style = MaterialTheme.typography.titleMedium)
            listOf(
                DiscountKind.NONE to "Tanpa diskon",
                DiscountKind.NOMINAL to "Nominal (Rp)",
                DiscountKind.PERCENT to "Persentase (%)"
            ).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Touch.min).clickable { kind = id },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = kind == id, onClick = { kind = id })
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (kind != DiscountKind.NONE) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (kind == DiscountKind.PERCENT) "Persen" else "Nominal") },
                    singleLine = true,
                    shape = Radius.field,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text("Nilai diskon tidak valid", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AmountRow("Diskon diterapkan", OrderDiscount(kind, parsed ?: 0L).amountFor(subtotal), tone = Tone.WARNING)
            Button(
                onClick = {
                    if (kind == DiscountKind.NONE) onApply(OrderDiscount(DiscountKind.NONE, 0L))
                    else parsed?.let { onApply(OrderDiscount(kind, it)) }
                },
                enabled = kind == DiscountKind.NONE || parsed != null,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text("Terapkan Diskon", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPickerSheet(onDismiss: () -> Unit, onSelect: (CustomerEntity?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var customers by remember { mutableStateOf(emptyList<CustomerEntity>()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(query, page) {
        loading = true
        if (page == 0) delay(300)
        val requestedQuery = query
        val requestedPage = page
        val result = AppGraph.customers.page(requestedQuery, requestedPage, 50)
        if (query == requestedQuery && page == requestedPage) {
            total = result.second
            customers = if (requestedPage == 0) result.first else (customers + result.first).distinctBy { it.id }
            loading = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxHeight(0.85f)
                .padding(horizontal = Space.xl)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Pilih Customer", style = MaterialTheme.typography.titleMedium)
            SearchField(query, { query = it; page = 0 }, "Cari nama, HP, atau kode")
            OutlinedButton(
                onClick = { onSelect(null) },
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
            ) { Text("Tanpa customer") }
            when {
                customers.isEmpty() && loading -> LoadingState("Memuat customer…")
                customers.isEmpty() && query.isNotBlank() -> EmptyState(
                    title = "Tidak ada hasil",
                    message = "Tidak ada customer cocok untuk \"$query\".",
                    icon = Icons.Default.Person
                )
                customers.isEmpty() -> EmptyState(
                    title = "Belum ada customer",
                    message = "Tambahkan customer dari menu Customer terlebih dahulu.",
                    icon = Icons.Default.Person
                )
                else -> LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    items(customers, key = { it.id }) { c ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Touch.control)
                                .clickable { onSelect(c) }
                                .padding(vertical = Space.sm)
                        ) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(c.code, c.phone).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Tanpa kontak" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (customers.size < total) {
                        item {
                            TextButton(onClick = { page += 1 }, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Payment sheet. Allocation semantics come entirely from PaymentAllocation:
 * change is cash-only, non-cash overpay is rejected, and settlement must equal the total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(
    total: Long,
    methods: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onComplete: (Map<String, Long>, Map<String, String>) -> Unit
) {
    var methodId by remember(methods) { mutableStateOf(methods.firstOrNull()?.first ?: "") }
    var amount by remember { mutableStateOf(total.toString()) }
    var reference by remember { mutableStateOf("") }
    var tenders by remember { mutableStateOf(linkedMapOf<String, Long>()) }
    var references by remember { mutableStateOf(linkedMapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val settled = PaymentAllocation.settle(tenders, total)
    val remaining = (total - settled.settled.values.sum()).coerceAtLeast(0)
    val isCash = methodId == PaymentAllocation.CASH

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxHeight(0.92f)
                .padding(horizontal = Space.xl)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pembayaran", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                MoneyText(total, style = MaterialTheme.typography.titleLarge, weight = FontWeight.Bold)
            }
            if (methods.isEmpty()) {
                EmptyState(
                    title = "Tidak ada metode pembayaran aktif",
                    message = "Aktifkan minimal satu metode pembayaran pada Pengaturan sebelum menerima pembayaran.",
                    icon = Icons.Default.Payments
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    SectionHeader("Metode")
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        methods.forEach { (id, name) ->
                            androidx.compose.material3.FilterChip(
                                selected = methodId == id,
                                onClick = { methodId = id; amount = remaining.toString(); error = null },
                                label = { Text(name) },
                                shape = Radius.control,
                                modifier = Modifier.heightIn(min = Touch.min)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it; error = null },
                        label = { Text("Nominal ${Labels.paymentMethod(methodId)}") },
                        singleLine = true,
                        shape = Radius.field,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isCash) {
                        SectionHeader("Nominal cepat")
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            val quick = quickCashAmounts(remaining)
                            AssistChip(onClick = { amount = remaining.toString() }, label = { Text("Uang pas") })
                            quick.forEach { value ->
                                AssistChip(onClick = { amount = value.toString() }, label = { Text(Money.num(value)) })
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = reference,
                            onValueChange = { reference = it },
                            label = { Text("Nomor referensi (opsional)") },
                            singleLine = true,
                            shape = Radius.field,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val n = Money.parseOrNull(amount)
                            when {
                                methodId.isBlank() -> error = "Pilih metode pembayaran terlebih dahulu"
                                n == null || n <= 0 -> error = "Nominal tidak valid"
                                else -> {
                                    val existing = tenders[methodId] ?: 0L
                                    val sum = Money.addExact(existing, n)
                                    if (sum == null) error = "Nominal pembayaran melampaui batas"
                                    else {
                                        val copy = LinkedHashMap(tenders).apply { put(methodId, sum) }
                                        val candidate = PaymentAllocation.settle(copy, total)
                                        if (methodId != PaymentAllocation.CASH && candidate.settled.isEmpty()) {
                                            error = "Pembayaran non-tunai tidak boleh melebihi sisa tagihan"
                                        } else {
                                            tenders = copy
                                            if (reference.isNotBlank()) {
                                                references = LinkedHashMap(references).apply { put(methodId, reference) }
                                            }
                                            amount = (total - candidate.settled.values.sum()).coerceAtLeast(0).toString()
                                            reference = ""
                                            error = null
                                        }
                                    }
                                }
                            }
                        },
                        shape = Radius.field,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Touch.control)
                    ) { Text("Tambah Pembayaran") }

                    if (tenders.isNotEmpty()) {
                        SectionHeader("Alokasi pembayaran")
                        tenders.forEach { (code, value) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(Labels.paymentMethod(code), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                MoneyText(value, weight = FontWeight.Medium)
                                IconButton(
                                    onClick = { tenders = LinkedHashMap(tenders).apply { remove(code) } },
                                    modifier = Modifier.size(Touch.min)
                                ) { Icon(Icons.Default.Close, contentDescription = "Hapus ${Labels.paymentMethod(code)}") }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    AmountRow("Total tender", settled.tendered)
                    if (settled.change > 0) AmountRow("Kembalian", settled.change, tone = Tone.SUCCESS)
                    if (settled.shortfall > 0) AmountRow("Kurang", settled.shortfall, tone = Tone.DANGER)
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        val finalSettled = PaymentAllocation.settle(tenders, total)
                        if (finalSettled.shortfall > 0 || finalSettled.settled.values.sum() != total) {
                            error = "Pembayaran belum mencukupi atau alokasi tidak valid"
                        } else onComplete(tenders, references)
                    },
                    enabled = methods.isNotEmpty(),
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
                ) { Text("Konfirmasi Pembayaran", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Useful cash denominations at or above the remaining amount. */
internal fun quickCashAmounts(remaining: Long): List<Long> {
    if (remaining <= 0) return emptyList()
    val denominations = listOf(5_000L, 10_000L, 20_000L, 50_000L, 100_000L)
    val rounded = denominations.mapNotNull { step ->
        val up = ((remaining + step - 1) / step) * step
        up.takeIf { it > remaining }
    }
    return (denominations.filter { it > remaining } + rounded)
        .distinct()
        .sorted()
        .take(4)
}

/** Clean success surface that returns the operator to a ready POS quickly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutSuccessSheet(
    payload: ReceiptPayload,
    onNewTransaction: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onNewTransaction, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.xxl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TrapezoStatus.success)
                Column {
                    Text("Pembayaran Berhasil", style = MaterialTheme.typography.titleMedium)
                    Text(
                        payload.sale.invoiceNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AmountRow("Total", payload.sale.grandTotal, emphasize = true)
            payload.payments.forEach { AmountRow(Labels.paymentMethod(it.method), it.amount) }
            if (payload.sale.changeAmount > 0) {
                AmountRow("Kembalian", payload.sale.changeAmount, tone = Tone.SUCCESS)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onPrint,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Cetak")
                }
                OutlinedButton(
                    onClick = onShare,
                    shape = Radius.field,
                    modifier = Modifier.weight(1f).heightIn(min = Touch.control)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Bagikan")
                }
            }
            Button(
                onClick = onNewTransaction,
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth().heightIn(min = Touch.primaryAction)
            ) { Text("Transaksi Baru", fontWeight = FontWeight.Bold) }
        }
    }
}
