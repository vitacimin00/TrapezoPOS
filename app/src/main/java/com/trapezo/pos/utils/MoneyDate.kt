package com.trapezo.pos.utils

import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Money helpers: Rupiah formatting/parsing. All amounts are stored as Long rupiah units. */
object Money {

    private val nf: NumberFormat = DecimalFormat("#,##0")

    /** 150000 -> "Rp 150.000" */
    fun fmt(v: Long): String = "Rp " + nf.format(v)

    /** Same without currency prefix (for inputs) */
    fun num(v: Long): String = nf.format(v)

    /** Parse a user-typed money string ("12.500", "12500", "Rp 12,500") -> 12500 */
    fun parse(s: String): Long {
        val digits = s.filter { it.isDigit() }
        return if (digits.isEmpty()) 0L else digits.toLong()
    }

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
