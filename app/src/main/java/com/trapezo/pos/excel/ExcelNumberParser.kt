package com.trapezo.pos.excel

/**
 * Parses common Excel/retail numeric display forms without silently changing
 * thousands separators into decimals. Supports Indonesian and international
 * formats, e.g. 1.000,50 and 1,000.50.
 */
object ExcelNumberParser {
    fun parse(raw: String): Double? {
        var value = raw.trim()
            .replace("Rp", "", ignoreCase = true)
            .replace(" ", "")
            .replace("_", "")
        if (value.isBlank()) return null
        if (!value.matches(Regex("[-+]?([0-9.,]+)"))) return null

        val comma = value.lastIndexOf(',')
        val dot = value.lastIndexOf('.')
        value = when {
            comma >= 0 && dot >= 0 -> {
                // The rightmost separator is decimal; the other is grouping.
                if (comma > dot) value.replace(".", "").replace(',', '.')
                else value.replace(",", "")
            }
            comma >= 0 -> normalizeSingleSeparator(value, ',')
            dot >= 0 -> normalizeSingleSeparator(value, '.')
            else -> value
        }
        return value.toDoubleOrNull()
    }

    private fun normalizeSingleSeparator(value: String, separator: Char): String {
        val parts = value.split(separator)
        if (parts.size == 2) {
            val tail = parts[1]
            // A single 3-digit tail conventionally means grouping (1.000/1,000).
            return if (tail.length == 3 && parts[0].replace("+", "").replace("-", "").isNotEmpty()) {
                parts[0] + tail
            } else parts[0] + "." + tail
        }
        // Repeated separator: all trailing groups of 3 => grouping, otherwise
        // use the final separator as decimal and remove earlier grouping marks.
        return if (parts.drop(1).all { it.length == 3 }) parts.joinToString("")
        else parts.dropLast(1).joinToString("") + "." + parts.last()
    }
}
