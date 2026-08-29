package com.trapezo.pos.utils

import java.math.BigInteger

/**
 * API-safe replacement for `BigInteger.longValueExact()`.
 *
 * `java.math.BigInteger#longValueExact` only exists on the Android platform from API 31, but
 * Trapezo POS supports `minSdk = 26`. Calling it on API 26–30 throws `NoSuchMethodError` at
 * runtime, which would crash pricing and refund allocation on older devices.
 *
 * The semantics are IDENTICAL to the JDK method: return the exact `Long` value, or throw
 * `ArithmeticException` when the value does not fit in a signed 64-bit long. No rounding,
 * truncation, or clamping is introduced — money math stays exact.
 */
fun BigInteger.toLongExactCompat(): Long {
    if (bitLength() > 63) throw ArithmeticException("BigInteger out of long range")
    return toLong()
}
