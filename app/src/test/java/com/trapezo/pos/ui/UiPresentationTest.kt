package com.trapezo.pos.ui

import androidx.compose.ui.unit.dp
import com.trapezo.pos.ui.components.Labels
import com.trapezo.pos.ui.components.WidthClass
import com.trapezo.pos.ui.components.widthClassFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Track F presentation logic: responsive breakpoints, role-based navigation, and
 * machine-code-to-copy mapping. No UI framework required.
 */
class UiPresentationTest {

    @Test fun widthClass_followsMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, widthClassFor(360.dp))
        assertEquals(WidthClass.COMPACT, widthClassFor(599.dp))
        assertEquals(WidthClass.MEDIUM, widthClassFor(600.dp))
        assertEquals(WidthClass.MEDIUM, widthClassFor(839.dp))
        assertEquals(WidthClass.EXPANDED, widthClassFor(840.dp))
        assertEquals(WidthClass.EXPANDED, widthClassFor(1280.dp))
    }

    @Test fun cashierNavigation_excludesAdminOnlyModules() {
        val cashier = Navigation.destinationsFor("CASHIER")
        assertFalse(cashier.contains(AppDestination.SETTINGS))
        assertFalse(cashier.contains(AppDestination.REPORTS))
        assertFalse(cashier.contains(AppDestination.DASHBOARD))
        assertFalse(cashier.contains(AppDestination.CUSTOMERS))
        assertTrue(cashier.contains(AppDestination.POS))
        assertTrue(cashier.contains(AppDestination.SHIFT))
        assertTrue(cashier.contains(AppDestination.TRANSACTIONS))
    }

    @Test fun adminNavigation_containsEveryBusinessModule() {
        val admin = Navigation.destinationsFor("ADMIN")
        listOf(
            AppDestination.DASHBOARD, AppDestination.POS, AppDestination.SHIFT,
            AppDestination.PRODUCTS, AppDestination.INVENTORY, AppDestination.TRANSACTIONS,
            AppDestination.CUSTOMERS, AppDestination.REPORTS, AppDestination.SETTINGS
        ).forEach { assertTrue("missing $it", admin.contains(it)) }
    }

    @Test fun compactNavigation_partitionsWithoutLosingDestinations() {
        listOf("ADMIN", "CASHIER").forEach { role ->
            val all = Navigation.destinationsFor(role).toSet()
            val primary = Navigation.compactPrimary(role).toSet()
            val overflow = Navigation.compactOverflow(role).toSet()
            assertTrue("primary must be a subset for $role", all.containsAll(primary))
            assertEquals("primary+overflow must cover all destinations for $role", all, primary + overflow)
            assertTrue("primary and overflow must not intersect for $role", (primary intersect overflow).isEmpty())
            assertTrue("bottom bar stays within 5 slots for $role", primary.size <= 4)
        }
    }

    @Test fun startDestination_matchesRoleResponsibility() {
        assertEquals(AppDestination.DASHBOARD, Navigation.startDestination("ADMIN"))
        assertEquals(AppDestination.POS, Navigation.startDestination("CASHIER"))
    }

    @Test fun machineCodes_areMappedToHumanCopy() {
        assertEquals("Kartu Kredit", Labels.paymentMethod("CREDIT_CARD"))
        assertEquals("Tunai", Labels.paymentMethod("CASH"))
        assertEquals("Selesai", Labels.transactionStatus("COMPLETED"))
        assertEquals("Refund Sebagian", Labels.transactionStatus("PARTIALLY_REFUNDED"))
        assertEquals("Penyesuaian Keluar", Labels.movementType("ADJUST_REMOVE"))
        assertEquals("Update dari Excel", Labels.movementType("IMPORT_UPDATE"))
        assertEquals("Kas Masuk", Labels.cashMovement("CASH_IN"))
        assertEquals("Administrator", Labels.role("ADMIN"))
        assertEquals("Kasir", Labels.role("CASHIER"))
        assertEquals("Stok Menipis", Labels.stockFilter("LOW"))
        assertEquals("Stok Habis", Labels.stockFilter("OUT"))
    }

    @Test fun unknownCodes_fallBackToRawValueInsteadOfCrashing() {
        assertEquals("SOMETHING_NEW", Labels.paymentMethod("SOMETHING_NEW"))
        assertEquals("WEIRD_STATUS", Labels.transactionStatus("WEIRD_STATUS"))
    }

    @Test fun quickCashAmounts_onlyOffersAmountsAboveRemaining() {
        val remaining = 37_500L
        val quick = quickCashAmountsForTest(remaining)
        assertTrue("must offer options", quick.isNotEmpty())
        assertTrue("never below remaining", quick.all { it > remaining })
        assertEquals("sorted ascending", quick.sorted(), quick)
        assertTrue("bounded list", quick.size <= 4)
    }

    @Test fun quickCashAmounts_areEmptyWhenNothingRemains() {
        assertTrue(quickCashAmountsForTest(0L).isEmpty())
    }
}

/** Bridge to the POS screen's internal helper so it stays testable without Compose. */
private fun quickCashAmountsForTest(remaining: Long): List<Long> =
    com.trapezo.pos.ui.screens.quickCashAmounts(remaining)
