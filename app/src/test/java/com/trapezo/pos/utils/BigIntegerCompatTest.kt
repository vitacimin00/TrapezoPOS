package com.trapezo.pos.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/**
 * Locks the semantics of the API-26 compatibility shim for `BigInteger.longValueExact()`.
 * PricingEngine and RefundRules depend on these exact boundaries; do not loosen them.
 */
class BigIntegerCompatTest {

    @Test fun maxLongValue_isExact() {
        val v = BigInteger.valueOf(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, v.toLongExactCompat())
    }

    @Test fun minLongValue_isExact() {
        val v = BigInteger.valueOf(Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, v.toLongExactCompat())
    }

    @Test fun zero_isExact() {
        assertEquals(0L, BigInteger.ZERO.toLongExactCompat())
    }

    @Test fun maxLongValuePlusOne_throwsArithmeticException() {
        val v = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
        assertThrows(ArithmeticException::class.java) { v.toLongExactCompat() }
    }

    @Test fun minLongValueMinusOne_throwsArithmeticException() {
        val v = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)
        assertThrows(ArithmeticException::class.java) { v.toLongExactCompat() }
    }

    @Test fun matchesJdkLongValueExact_acrossBoundaryValues() {
        // Cross-check against the JDK reference implementation directly (this JVM test runs on
        // a desktop JDK where longValueExact is always available), to prove the compat shim is a
        // faithful substitute rather than merely "close enough".
        val samples = listOf(
            BigInteger.valueOf(Long.MAX_VALUE),
            BigInteger.valueOf(Long.MIN_VALUE),
            BigInteger.ZERO,
            BigInteger.valueOf(-1L),
            BigInteger.valueOf(1L),
            BigInteger.valueOf(123_456_789_012L)
        )
        for (s in samples) {
            assertEquals(s.longValueExact(), s.toLongExactCompat())
        }
    }
}
