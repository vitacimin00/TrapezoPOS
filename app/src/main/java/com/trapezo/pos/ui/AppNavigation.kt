package com.trapezo.pos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.Tone
import com.trapezo.pos.ui.components.currentWidthClass
import com.trapezo.pos.ui.components.isExpanded
import com.trapezo.pos.ui.theme.Radius
import com.trapezo.pos.ui.theme.Space

/** Destinations available in the business app shell. */
enum class AppDestination(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    POS("Kasir", Icons.Default.PointOfSale),
    SHIFT("Shift", Icons.Default.Schedule),
    PRODUCTS("Produk", Icons.Default.Inventory2),
    INVENTORY("Stok", Icons.Default.Category),
    TRANSACTIONS("Transaksi", Icons.Default.ReceiptLong),
    CUSTOMERS("Customer", Icons.Default.People),
    REPORTS("Laporan", Icons.Default.Assessment),
    SETTINGS("Pengaturan", Icons.Default.Settings)
}

/**
 * Role-based navigation. A cashier never sees destinations they cannot operate, and the
 * two roles get different primary sets because their jobs differ.
 *
 * Repository-level authorization remains authoritative; this is convenience only.
 */
object Navigation {
    fun destinationsFor(role: String): List<AppDestination> =
        if (role == "ADMIN") {
            listOf(
                AppDestination.DASHBOARD,
                AppDestination.POS,
                AppDestination.SHIFT,
                AppDestination.PRODUCTS,
                AppDestination.INVENTORY,
                AppDestination.TRANSACTIONS,
                AppDestination.CUSTOMERS,
                AppDestination.REPORTS,
                AppDestination.SETTINGS
            )
        } else {
            listOf(
                AppDestination.POS,
                AppDestination.SHIFT,
                AppDestination.TRANSACTIONS,
                AppDestination.PRODUCTS
            )
        }

    /** High-frequency destinations shown directly in the compact bottom bar. */
    fun compactPrimary(role: String): List<AppDestination> =
        if (role == "ADMIN") {
            listOf(
                AppDestination.POS,
                AppDestination.TRANSACTIONS,
                AppDestination.PRODUCTS,
                AppDestination.DASHBOARD
            )
        } else {
            listOf(
                AppDestination.POS,
                AppDestination.TRANSACTIONS,
                AppDestination.SHIFT
            )
        }

    /** Remaining destinations reachable from the compact "Lainnya" sheet. */
    fun compactOverflow(role: String): List<AppDestination> =
        destinationsFor(role) - compactPrimary(role).toSet()

    fun startDestination(role: String): AppDestination =
        if (role == "ADMIN") AppDestination.DASHBOARD else AppDestination.POS
}

/** Trapezoid brand mark — the Trapezo identity, drawn not imported. */
@Composable
fun TrapezoMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(this@Canvas.size.width * 0.22f, this@Canvas.size.height * 0.20f)
            lineTo(this@Canvas.size.width * 0.78f, this@Canvas.size.height * 0.20f)
            lineTo(this@Canvas.size.width * 0.96f, this@Canvas.size.height * 0.80f)
            lineTo(this@Canvas.size.width * 0.04f, this@Canvas.size.height * 0.80f)
            close()
        }
        drawPath(path, color)
    }
}

/** Left navigation rail for expanded/tablet widths. */
@Composable
fun AppNavigationRail(
    destinations: List<AppDestination>,
    current: AppDestination,
    userName: String,
    role: String,
    onSelect: (AppDestination) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settingsGroup = destinations.filter { it == AppDestination.SETTINGS }
    val mainGroup = destinations - settingsGroup.toSet()
    Row(modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            header = {
                Column(
                    Modifier.padding(vertical = Space.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    TrapezoMark()
                    Text("Trapezo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            },
            modifier = Modifier.width(96.dp)
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.xs)
            ) {
                mainGroup.forEach { item ->
                    NavigationRailItem(
                        selected = current == item,
                        onClick = { onSelect(item) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
            }
            Column(
                Modifier.padding(bottom = Space.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.xs)
            ) {
                HorizontalDivider(Modifier.padding(vertical = Space.sm), color = MaterialTheme.colorScheme.outlineVariant)
                settingsGroup.forEach { item ->
                    NavigationRailItem(
                        selected = current == item,
                        onClick = { onSelect(item) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    )
                }
                NavigationRailItem(
                    selected = false,
                    onClick = onLogout,
                    icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Keluar") },
                    label = { Text("Keluar", style = MaterialTheme.typography.labelSmall) }
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = Radius.badge,
                    modifier = Modifier.padding(horizontal = Space.sm)
                ) {
                    Column(
                        Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            userName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            Labels.role(role),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Bottom navigation for compact/phone widths, with an overflow entry for admin modules. */
@Composable
fun AppBottomNavigation(
    primary: List<AppDestination>,
    current: AppDestination,
    hasOverflow: Boolean,
    onSelect: (AppDestination) -> Unit,
    onOverflow: () -> Unit
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            primary.forEach { item ->
                NavigationBarItem(
                    selected = current == item,
                    onClick = { onSelect(item) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label, maxLines = 1) }
                )
            }
            if (hasOverflow) {
                NavigationBarItem(
                    selected = current !in primary,
                    onClick = onOverflow,
                    icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "Menu lainnya") },
                    label = { Text("Lainnya", maxLines = 1) }
                )
            }
        }
    }
}
