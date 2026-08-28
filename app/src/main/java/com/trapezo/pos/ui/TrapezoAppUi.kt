package com.trapezo.pos.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trapezo.pos.AppGraph
import com.trapezo.pos.ui.screens.ProductsScreen
import com.trapezo.pos.ui.screens.PosScreen
import com.trapezo.pos.ui.screens.TransactionsScreen
import com.trapezo.pos.ui.screens.InventoryScreen
import com.trapezo.pos.ui.screens.CustomersScreen
import com.trapezo.pos.ui.screens.ReportsScreen
import com.trapezo.pos.ui.screens.SettingsScreen
import com.trapezo.pos.utils.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    POS("Kasir", Icons.Default.PointOfSale),
    PRODUCTS("Produk", Icons.Default.Inventory2),
    INVENTORY("Inventory", Icons.Default.Category),
    TRANSACTIONS("Transaksi", Icons.Default.ReceiptLong),
    CUSTOMERS("Customer", Icons.Default.People),
    REPORTS("Laporan", Icons.Default.Assessment),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun TrapezoRoot(vm: AppViewModel = viewModel()) {
    val session by vm.session.collectAsState()
    if (session.user == null) LoginScreen(
        loading = session.loading,
        error = session.error,
        onLogin = vm::login
    ) else MainShell(user = session.user!!, onLogout = vm::logout)
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("▱", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                Text("Trapezo POS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Kasir modern, cepat, dan tetap offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (password.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onLogin(username, password) }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (loading) "Memeriksa…" else "MASUK", fontWeight = FontWeight.Bold)
                }
                Text("Login pertama: admin / admin123", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(user: com.trapezo.pos.data.entity.UserEntity, onLogout: () -> Unit) {
    var destination by remember { mutableStateOf(AppDestination.DASHBOARD) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val primary = listOf(
        AppDestination.DASHBOARD,
        AppDestination.POS,
        AppDestination.PRODUCTS,
        AppDestination.TRANSACTIONS,
        AppDestination.SETTINGS
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(vertical = 18.dp, horizontal = 12.dp)) {
                    Text("Trapezo POS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${user.name} • ${user.role}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                AppDestination.entries
                    .filter { it != AppDestination.REPORTS || user.role == "ADMIN" }
                    .forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = destination == item,
                        onClick = {
                            destination = item
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Keluar") },
                    selected = false,
                    onClick = onLogout,
                    icon = { Icon(Icons.Default.Close, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Trapezo POS", fontWeight = FontWeight.Bold)
                            Text(destination.label, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Buka menu")
                        }
                    },
                    actions = { TextButton(onClick = onLogout) { Text(user.name) } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                // Elevated navigation bar with a clear divider so content visually
                // "ends" above it instead of bleeding into the bar.
                androidx.compose.foundation.layout.Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        primary.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, item.label) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                // Smooth fade between modules — keeps navigation feeling responsive, not jumpy.
                Crossfade(targetState = destination, label = "screenFade") { current ->
                    when (current) {
                        AppDestination.DASHBOARD -> DashboardScreen()
                        AppDestination.POS -> PosScreen(user)
                        AppDestination.PRODUCTS -> ProductsScreen(user.id, user.role == "ADMIN")
                        AppDestination.INVENTORY -> InventoryScreen(user.id, user.role == "ADMIN")
                        AppDestination.TRANSACTIONS -> TransactionsScreen(user)
                        AppDestination.CUSTOMERS -> CustomersScreen(user.role == "ADMIN" || user.role == "CASHIER")
                        AppDestination.REPORTS -> if (user.role == "ADMIN") ReportsScreen() else ReportsDenied()
                        AppDestination.SETTINGS -> SettingsScreen(user)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    var revenue by remember { mutableStateOf(0L) }
    var tx by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf(0L) }
    var cash by remember { mutableStateOf(0L) }
    var nonCash by remember { mutableStateOf(0L) }
    var low by remember { mutableStateOf(0) }
    var empty by remember { mutableStateOf(0) }
    var activeShift by remember { mutableStateOf(false) }
    var activeCashier by remember { mutableStateOf<String?>(null) }
    var weekly by remember { mutableStateOf(emptyList<com.trapezo.pos.data.dao.SaleDao.DailyTotalRow>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val stat = AppGraph.sales.todayStats()
            revenue = stat.total
            tx = stat.cnt
            val from = com.trapezo.pos.utils.Dates.startOfDay()
            val to = com.trapezo.pos.utils.Dates.endOfDay()
            items = AppGraph.sales.itemsSold(from, to).totalQty?.toLong() ?: 0L
            val methods = AppGraph.sales.methodBreakdown(from, to)
            cash = methods.firstOrNull { it.method == "CASH" }?.total ?: 0L
            nonCash = methods.filter { it.method != "CASH" }.sumOf { it.total ?: 0L }
            low = AppGraph.products.lowStock().size
            empty = AppGraph.products.outOfStock().size
            val open = AppGraph.db.shiftDao().anyOpenShift()
            activeShift = open != null
            activeCashier = open?.userNameSnapshot
            weekly = AppGraph.sales.dailySeries(7)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ringkasan hari ini", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Penjualan", Money.fmt(revenue), Modifier.weight(1f))
            MetricCard("Transaksi", tx.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Tunai", Money.fmt(cash), Modifier.weight(1f))
            MetricCard("Non-tunai", Money.fmt(nonCash), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Item terjual", items.toString(), Modifier.weight(1f))
            MetricCard("Kasir aktif", activeCashier ?: "—", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Stok rendah", low.toString(), Modifier.weight(1f))
            MetricCard("Stok habis", empty.toString(), Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Shift kasir", fontWeight = FontWeight.SemiBold)
                Text(
                    if (activeShift) "Shift sedang aktif" else "Belum ada shift aktif",
                    color = if (activeShift) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Penjualan & transaksi 7 hari", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SevenDayChart(weekly)
            }
        }
    }
}

@Composable
private fun SevenDayChart(rows: List<com.trapezo.pos.data.dao.SaleDao.DailyTotalRow>) {
    val byDay = rows.associateBy { it.dayStart }
    val days = (6 downTo 0).map { offset -> com.trapezo.pos.utils.Dates.startOfDay(System.currentTimeMillis() - offset * com.trapezo.pos.utils.Dates.DAY_MS) }
    val values = days.map { byDay[it]?.total ?: 0L }
    val counts = days.map { byDay[it]?.cnt ?: 0 }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(118.dp)) {
        val gap = size.width / (days.size * 2f)
        val barWidth = gap.coerceAtLeast(8f)
        values.forEachIndexed { index, value ->
            val h = if (value == 0L) 3f else (value.toFloat() / maxValue * (size.height - 26f)).coerceAtLeast(3f)
            val x = gap * (index * 2 + 1)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - 22f - h),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(com.trapezo.pos.utils.Dates.weekdayShort(day), style = MaterialTheme.typography.labelSmall)
                Text(counts[i].toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text("Batang: omzet • angka bawah: transaksi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) { Text(title, color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.height(8.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable private fun ReportsDenied() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Laporan hanya untuk ADMIN", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Akun CASHIER dapat melihat riwayat transaksi di menu Transaksi.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
