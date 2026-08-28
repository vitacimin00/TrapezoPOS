package com.trapezo.pos.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyOverflowTest {
    @Test fun normalInput_parsesToLong() {
        assertEquals(12500L, Money.parseOrNull("12.500"))
        assertEquals(12500L, Money.parseOrNull("Rp 12,500"))
    }

    @Test fun blank_invalidUnlessAllowed() {
        assertNull(Money.parseOrNull(""))
        assertEquals(0L, Money.parseOrNull("", allowBlank = true))
    }

    @Test fun nonNumeric_isInvalid() {
        assertNull(Money.parseOrNull("abc"))
    }

    @Test fun longMaxValue_isInvalidBeyondCeiling() {
        assertNull(Money.parseOrNull(Long.MAX_VALUE.toString()))
    }

    @Test fun longMaxValuePlusOne_isInvalid() {
        // 9223372036854775808 overflows Long entirely.
        assertNull(Money.parseOrNull("9223372036854775808"))
    }

    @Test fun thirtyDigitInput_isInvalidNotSaturated() {
        assertNull(Money.parseOrNull("1".repeat(30)))
    }

    @Test fun maxOperationalValue_isValid() {
        assertEquals(Money.MAX_RUPIAH, Money.parseOrNull(Money.MAX_RUPIAH.toString()))
    }

    @Test fun maxOperationalValuePlusOne_isInvalid() {
        assertNull(Money.parseOrNull((Money.MAX_RUPIAH + 1).toString()))
    }

    @Test fun negativeInput_isInvalid() {
        assertNull(Money.parseOrNull("-100"))
    }

    @Test fun addExact_overflowReturnsNull() {
        assertNull(Money.addExact(Long.MAX_VALUE, 1))
        assertEquals(30L, Money.addExact(10, 20))
    }

    @Test fun subtractExact_overflowReturnsNull() {
        assertNull(Money.subtractExact(Long.MIN_VALUE, 1))
        assertEquals(-10L, Money.subtractExact(10, 20))
    }

    @Test fun deprecatedParse_neverThrowsOnOverflow() {
        // Backward-compat shim must not throw; callers migrating to parseOrNull get strictness.
        Money.parse("9".repeat(40))
    }
}
