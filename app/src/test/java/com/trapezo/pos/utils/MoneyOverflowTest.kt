package com.trapezo.pos.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyOverflowTest {
    @Test fun normalInput_parsesToLong() {
        assertEquals(12500L, Money.parse("12.500"))
        assertEquals(12500L, Money.parse("Rp 12,500"))
    }

    @Test fun emptyInput_returnsZero() {
        assertEquals(0L, Money.parse(""))
        assertEquals(0L, Money.parse("abc"))
    }

    @Test fun absurdlyLongDigits_doesNotThrowAndClamps() {
        // 30 digits far exceeds Long.MAX_VALUE; must not throw NumberFormatException.
        val result = Money.parse("1".repeat(30))
        assertEquals(Long.MAX_VALUE, result)
    }

    @Test fun exactLongMax_roundTrips() {
        assertEquals(Long.MAX_VALUE, Money.parse(Long.MAX_VALUE.toString()))
    }
}
