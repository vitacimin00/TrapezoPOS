package com.trapezo.pos.ui.screens

import com.trapezo.pos.domain.model.PaymentAllocation
import com.trapezo.pos.utils.Money

/**
 * Pure, Compose-free state for the payment sheet.
 *
 * All settlement math delegates to [PaymentAllocation] — this class invents no pricing
 * or allocation logic. It only keeps the *transient* draw state (selected method, the
 * amount/reference fields being typed) and the *committed* tenders/references consistent
 * with each other, which is where the UI used to leak stale values:
 *
 *  - switching the active method clears the temporary reference so a QRIS number can
 *    never be carried onto a TRANSFER tender;
 *  - removing a tender removes its stored reference too;
 *  - the default amount always reflects the recalculated remaining after any change;
 *  - re-adding a method never resurrects a previously removed reference.
 */
internal data class PaymentDraft(
    val total: Long,
    val methodId: String,
    val amount: String,
    val reference: String = "",
    val tenders: Map<String, Long> = linkedMapOf(),
    val references: Map<String, String> = linkedMapOf()
) {
    companion object {
        fun start(total: Long, firstMethod: String): PaymentDraft =
            PaymentDraft(total = total, methodId = firstMethod, amount = total.toString())
    }

    /** Settlement of the committed tenders, straight from the locked allocator. */
    val settled: PaymentAllocation.Result get() = PaymentAllocation.settle(tenders, total)

    /** Unsettled remainder after committed tenders. */
    val remaining: Long get() = (total - settled.settled.values.sum()).coerceAtLeast(0)

    /** Switch the active method; the temporary reference is cleared (rule A). */
    fun selectMethod(id: String): PaymentDraft =
        copy(methodId = id, reference = "", amount = remaining.toString())

    fun changeAmount(value: String): PaymentDraft = copy(amount = value)

    fun changeReference(value: String): PaymentDraft = copy(reference = value)

    /** Remove a tender and its reference; the default amount is recalculated (rules B/D). */
    fun removeTender(code: String): PaymentDraft {
        val t = LinkedHashMap(tenders).apply { remove(code) }
        val r = LinkedHashMap(references).apply { remove(code) }
        val next = copy(tenders = t, references = r)
        return next.copy(amount = next.remaining.toString())
    }

    /**
     * Commit the current amount + reference as a tender for the active method.
     *
     * Mirrors the previous inline validation exactly: cash may overpay (change is
     * produced by [PaymentAllocation]); a non-cash tender that exceeds the remaining
     * bill is rejected. On success the temporary reference is cleared and the default
     * amount becomes the new remaining.
     */
    fun addTender(): AddTenderOutcome {
        if (methodId.isBlank()) return AddTenderOutcome.Rejected("Pilih metode pembayaran terlebih dahulu")
        val n = Money.parseOrNull(amount)
        if (n == null || n <= 0) return AddTenderOutcome.Rejected("Nominal tidak valid")
        val existing = tenders[methodId] ?: 0L
        val sum = Money.addExact(existing, n)
            ?: return AddTenderOutcome.Rejected("Nominal pembayaran melampaui batas")
        val t = LinkedHashMap(tenders).apply { put(methodId, sum) }
        val candidate = PaymentAllocation.settle(t, total)
        if (methodId != PaymentAllocation.CASH && candidate.settled.isEmpty()) {
            return AddTenderOutcome.Rejected("Pembayaran non-tunai tidak boleh melebihi sisa tagihan")
        }
        val r = LinkedHashMap(references)
        if (reference.isNotBlank()) r[methodId] = reference
        return AddTenderOutcome.Accepted(
            copy(
                tenders = t,
                references = r,
                amount = (total - candidate.settled.values.sum()).coerceAtLeast(0).toString(),
                reference = ""
            )
        )
    }
}

/** Outcome of [PaymentDraft.addTender]. */
internal sealed interface AddTenderOutcome {
    data class Accepted(val draft: PaymentDraft) : AddTenderOutcome
    data class Rejected(val message: String) : AddTenderOutcome
}
