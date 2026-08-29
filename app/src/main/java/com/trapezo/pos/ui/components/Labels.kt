package com.trapezo.pos.ui.components

/**
 * Presentation-layer mapping from canonical database codes to human-facing Indonesian copy.
 *
 * IMPORTANT: these functions only translate for display. Stored values
 * (`CASH`, `COMPLETED`, `ADJUST_REMOVE`, …) remain the canonical persisted codes and are
 * never rewritten by the UI.
 */
object Labels {

    /** Payment method type code -> operator-facing label. */
    fun paymentMethod(code: String): String = when (code.uppercase()) {
        "CASH" -> "Tunai"
        "QRIS" -> "QRIS"
        "TRANSFER" -> "Transfer"
        "DEBIT" -> "Debit"
        "CREDIT_CARD" -> "Kartu Kredit"
        "EWALLET" -> "E-Wallet"
        "OTHER" -> "Lainnya"
        else -> code
    }

    /** Sale transaction status -> operator-facing label. */
    fun transactionStatus(code: String): String = when (code.uppercase()) {
        "COMPLETED" -> "Selesai"
        "PARTIALLY_REFUNDED" -> "Refund Sebagian"
        "REFUNDED" -> "Refund Penuh"
        "VOID" -> "Dibatalkan"
        else -> code
    }

    /** Payment settlement status -> operator-facing label. */
    fun paymentStatus(code: String): String = when (code.uppercase()) {
        "PAID" -> "Lunas"
        "UNPAID" -> "Belum Dibayar"
        "PARTIAL" -> "Sebagian"
        else -> code
    }

    /** Inventory movement type -> operator-facing label. */
    fun movementType(code: String): String = when (code.uppercase()) {
        "SALE" -> "Penjualan"
        "REFUND_IN" -> "Refund Masuk"
        "INITIAL" -> "Stok Awal"
        "IMPORT" -> "Import Excel"
        "IMPORT_UPDATE" -> "Update dari Excel"
        "ADJUST_ADD" -> "Penyesuaian Masuk"
        "ADJUST_REMOVE" -> "Penyesuaian Keluar"
        "ADJUST_SET" -> "Set Stok"
        else -> code
    }

    /** Shift status -> operator-facing label. */
    fun shiftStatus(code: String): String = when (code.uppercase()) {
        "OPEN" -> "Aktif"
        "CLOSED" -> "Ditutup"
        else -> code
    }

    /** Cash movement type -> operator-facing label. */
    fun cashMovement(code: String): String = when (code.uppercase()) {
        "CASH_IN" -> "Kas Masuk"
        "CASH_OUT" -> "Kas Keluar"
        else -> code
    }

    /** User role -> operator-facing label. */
    fun role(code: String): String = when (code.uppercase()) {
        "ADMIN" -> "Administrator"
        "CASHIER" -> "Kasir"
        else -> code
    }

    /** Stock adjustment mode -> operator-facing label. */
    fun adjustmentMode(code: String): String = when (code.uppercase()) {
        "ADD" -> "Tambah"
        "REMOVE" -> "Kurangi"
        "SET" -> "Set Stok"
        else -> code
    }

    /** Stock filter code -> operator-facing label. */
    fun stockFilter(code: String): String = when (code.uppercase()) {
        "ALL" -> "Semua"
        "LOW" -> "Stok Menipis"
        "OUT" -> "Stok Habis"
        else -> code
    }

    /** Product lifecycle filter code -> operator-facing label. */
    fun lifecycleFilter(code: String): String = when (code.uppercase()) {
        "ACTIVE" -> "Aktif"
        "INACTIVE" -> "Nonaktif"
        "ALL" -> "Semua"
        else -> code
    }
}
