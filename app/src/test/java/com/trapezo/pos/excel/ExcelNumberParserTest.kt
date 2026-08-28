package com.trapezo.pos.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExcelNumberParserTest {
    @Test fun strictInteger_acceptsDigitsOnlyWithinCap() {
        assertEquals(0L, ProductExcelService.strictInteger("0", 10))
        assertEquals(10L, ProductExcelService.strictInteger("10", 10))
        listOf("1,000", "1.000", "+1", "-1", " 1 ", "1a", "11", "9223372036854775808").forEach {
            assertNull("expected rejection: $it", ProductExcelService.strictInteger(it, 10))
        }
    }

    @Test fun parsesIndonesianAndInternationalGroupedNumbers() {
        assertEquals(1_000.0, ExcelNumberParser.parse("1.000")!!, 0.0001)
        assertEquals(1_000.0, ExcelNumberParser.parse("1,000")!!, 0.0001)
        assertEquals(1_000.50, ExcelNumberParser.parse("1.000,50")!!, 0.0001)
        assertEquals(1_000.50, ExcelNumberParser.parse("1,000.50")!!, 0.0001)
        assertEquals(15.5, ExcelNumberParser.parse("15,5")!!, 0.0001)
        assertEquals(15.5, ExcelNumberParser.parse("15.5")!!, 0.0001)
    }

    @Test fun rejectsNonNumericValue() {
        assertNull(ExcelNumberParser.parse("harga belum diisi"))
    }
}
