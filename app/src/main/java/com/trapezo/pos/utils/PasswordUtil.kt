package com.trapezo.pos.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure password hashing with PBKDF2-HMAC-SHA256.
 *
 * Canonical storage format (five fields):
 * `pbkdf2$sha256$iterations$saltBase64$hashBase64`
 *
 * The first Trapezo builds accidentally emitted a four-field legacy form:
 * `pbkdf2_sha256$iterations$saltBase64$hashBase64`.
 * Verification deliberately accepts it once, and AuthRepository upgrades it on
 * a successful login. Passwords are never stored or logged in plaintext.
 */
object PasswordUtil {

    private const val ALGO = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LEN = 256
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 2_000_000

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, ITERATIONS)
        val dollar = '$'
        return "pbkdf2${dollar}sha256${dollar}$ITERATIONS${dollar}${b64(salt)}${dollar}${b64(derived)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parsed = parse(stored) ?: return false
        val actual = derive(password, parsed.salt, parsed.iterations)
        return MessageDigest.isEqual(parsed.expected, actual)
    }

    /** True when a valid legacy or low-work-factor record should be refreshed. */
    fun needsRehash(stored: String): Boolean {
        val parsed = parse(stored) ?: return true
        return parsed.legacy || parsed.iterations < ITERATIONS
    }

    private data class Parsed(
        val iterations: Int,
        val salt: ByteArray,
        val expected: ByteArray,
        val legacy: Boolean
    )

    private fun parse(stored: String): Parsed? {
        val parts = stored.split('$')
        val legacy: Boolean
        val iterationText: String
        val saltText: String
        val expectedText: String
        when {
            parts.size == 5 && parts[0] == "pbkdf2" && parts[1] == "sha256" -> {
                legacy = false
                iterationText = parts[2]
                saltText = parts[3]
                expectedText = parts[4]
            }
            // Compatibility for the original first-run seed format.
            parts.size == 4 && parts[0] == "pbkdf2_sha256" -> {
                legacy = true
                iterationText = parts[1]
                saltText = parts[2]
                expectedText = parts[3]
            }
            else -> return null
        }
        val iterations = iterationText.toIntOrNull() ?: return null
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null
        val salt = unb64(saltText) ?: return null
        val expected = unb64(expectedText) ?: return null
        if (salt.size < 16 || expected.size != KEY_LEN / 8) return null
        return Parsed(iterations, salt, expected, legacy)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LEN)
        return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).encoded
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
