package com.trapezo.pos.utils

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Money helpers: Rupiah formatting/parsing. All amounts are stored as Long rupiah units. */
object Money {

    /** Maximum legitimate Rupiah amount for a single operational value (9 trillion). */
    const val MAX_RUPIAH = 9_000_000_000_000L

    /**
     * Indonesian Rupiah grouping is deterministic and locale-independent: a period is the
     * thousand separator regardless of the device's default locale. Relying on the default
     * locale made the same amount render as "Rp 28,500" or "Rp 28.500" per device.
     */
    private val nf: NumberFormat = DecimalFormat(
        "#,##0",
        DecimalFormatSymbols(Locale("id", "ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
    )

    /** 150000 -> "Rp 150.000" */
    fun fmt(v: Long): String = "Rp " + nf.format(v)

    /** Same without currency prefix (for inputs) */
    fun num(v: Long): String = nf.format(v)

    /**
     * Strictly parses a user-typed Rupiah string into a valid Long, or null when the
     * input is non-numeric, overflows Long, or exceeds the operational ceiling.
     * Overflow is never saturated into a plausible value.
     */
    fun parseOrNull(s: String, max: Long = MAX_RUPIAH, allowBlank: Boolean = false): Long? {
        val clean = s.trim()
        if (clean.isEmpty()) return if (allowBlank) 0L else null
        val digits = clean.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // A leading "-" or letters produce an entirely-digitless or partial string; only
        // accept when every non-formatting char was a digit (rejects "-100" as valid 100).
        if (clean.any { !it.isDigit() && it != '.' && it != ',' && it != ' ' && !it.isWhitespace() && it != 'R' && it != 'p' }) {
            // permit "Rp", thousand separators and spaces only
            if (clean.removePrefix("Rp").trim().any { !it.isDigit() && it != '.' && it != ',' && it != ' ' }) return null
        }
        if (digits.length > 18) return null // beyond Long capacity by construction
        val value = digits.toLongOrNull() ?: return null
        if (value < 0 || value > max) return null
        return value
    }

    /** Backward-compatible non-throwing parse used only where zero is a legitimate default. */
    @Deprecated("Use parseOrNull so overflow is rejected, not clamped", ReplaceWith("parseOrNull(s, allowBlank = true) ?: 0L"))
    fun parse(s: String): Long = parseOrNull(s, allowBlank = true) ?: 0L

    /** Overflow-safe add, returning null instead of wrapping. */
    fun addExact(a: Long, b: Long): Long? = try { Math.addExact(a, b) } catch (_: ArithmeticException) { null }

    /** Overflow-safe subtract, returning null instead of wrapping. */
    fun subtractExact(a: Long, b: Long): Long? = try { Math.subtractExact(a, b) } catch (_: ArithmeticException) { null }

    /** Quick rounding to nearest 100 for change display */
    fun roundTo(v: Long, step: Long = 100L): Long =
        if (step <= 1) v else ((v + step / 2) / step) * step
}

/** Date/time helpers (epoch millis based). */
object Dates {

    const val DAY_MS: Long = 86_400_000L

    fun startOfDay(epochMs: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfDay(epochMs: Long = System.currentTimeMillis()): Long = startOfDay(epochMs) + DAY_MS - 1

    fun daysAgoStart(n: Int): Long = startOfDay(System.currentTimeMillis() - n * DAY_MS)

    private fun sdf(pattern: String) = SimpleDateFormat(pattern, Locale("id", "ID"))

    fun dmy(ms: Long): String = sdf("dd/MM/yyyy").format(Date(ms))
    fun dmyhm(ms: Long): String = sdf("dd/MM/yyyy HH:mm").format(Date(ms))
    fun hhmm(ms: Long): String = sdf("HH:mm").format(Date(ms))
    fun ymd(ms: Long): String = sdf("yyyy-MM-dd").format(Date(ms))

    fun weekdayShort(ms: Long): String {
        val names = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }
}
