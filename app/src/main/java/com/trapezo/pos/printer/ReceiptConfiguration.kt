package com.trapezo.pos.printer

import com.trapezo.pos.data.repository.SettingsRepository
import com.trapezo.pos.data.repository.StoreRepository

/** Builds the receipt renderer configuration from persisted Store + Settings values. */
suspend fun receiptInfo(
    stores: StoreRepository,
    settings: SettingsRepository
): ReceiptService.StoreReceiptInfo {
    val store = stores.get()
    return ReceiptService.StoreReceiptInfo(
        name = store.name,
        address = store.address,
        phone = store.phone,
        footer = settings.raw("receipt.footer", "Terima kasih telah berbelanja!"),
        paperMm = settings.raw("receipt.paper", "80mm").filter { it.isDigit() }.toIntOrNull() ?: 80,
        showLogo = settings.bool("receipt.show_logo", true),
        showAddress = settings.bool("receipt.show_address", true),
        showPhone = settings.bool("receipt.show_phone", true),
        logoPath = store.logo
    )
}
