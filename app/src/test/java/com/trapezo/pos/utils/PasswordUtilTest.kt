package com.trapezo.pos.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordUtilTest {
    @Test fun newlyHashedPassword_verifiesAndRejectsWrongPassword() {
        val hash = PasswordUtil.hash("admin123")
        assertTrue(PasswordUtil.verify("admin123", hash))
        assertFalse(PasswordUtil.verify("wrong-password", hash))
    }

    @Test fun legacySeedHashFormat_remainsVerifiableAndRequestsUpgrade() {
        val canonical = PasswordUtil.hash("admin123").split('$')
        val legacy = "pbkdf2_sha256$${canonical[2]}$${canonical[3]}$${canonical[4]}"

        assertTrue(PasswordUtil.verify("admin123", legacy))
        assertTrue(PasswordUtil.needsRehash(legacy))
    }

    // ---- Track H2: work factor raised 120_000 -> 600_000 ----

    @Test fun newHashes_useCurrentWorkFactor() {
        val parts = PasswordUtil.hash("Password123").split('$')
        assertEquals("pbkdf2", parts[0])
        assertEquals("sha256", parts[1])
        assertEquals(600_000, parts[2].toInt())
        assertEquals(600_000, PasswordUtil.CURRENT_ITERATIONS)
    }

    @Test fun newHash_doesNotNeedRehash() {
        assertFalse(PasswordUtil.needsRehash(PasswordUtil.hash("Password123")))
    }

    /**
     * Builds a genuine record at an arbitrary (older) work factor by deriving with that exact
     * iteration count — not by editing the iteration field of a 600k hash, which would not
     * verify.
     */
    private fun hashAt(password: String, iterations: Int): String {
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val derived = javax.crypto.SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val enc = java.util.Base64.getEncoder()
        val d = '$'
        return "pbkdf2${d}sha256${d}$iterations${d}${enc.encodeToString(salt)}${d}${enc.encodeToString(derived)}"
    }

    @Test fun existingOld120kHash_stillVerifies() {
        val old = hashAt("Password123", 120_000)
        assertTrue("a valid 120k record must still authenticate", PasswordUtil.verify("Password123", old))
        assertFalse(PasswordUtil.verify("wrong-password", old))
    }

    @Test fun existingOld120kHash_needsRehash() {
        assertTrue(PasswordUtil.needsRehash(hashAt("Password123", 120_000)))
    }

    @Test fun rehashedRecord_landsOnCurrentWorkFactor() {
        val old = hashAt("Password123", 120_000)
        assertTrue(PasswordUtil.verify("Password123", old))
        assertTrue(PasswordUtil.needsRehash(old))

        // Simulates AuthRepository's upgrade-on-successful-login step.
        val upgraded = PasswordUtil.hash("Password123")
        assertEquals(600_000, upgraded.split('$')[2].toInt())
        assertTrue(PasswordUtil.verify("Password123", upgraded))
        assertFalse(PasswordUtil.needsRehash(upgraded))
    }

    // ---- bounded parser must still reject hostile records ----

    @Test fun malformedRecords_areRejected() {
        listOf(
            "",
            "not-a-hash",
            "pbkdf2\$sha256\$600000",                       // too few fields
            "pbkdf2\$md5\$600000\$c2FsdA==\$aGFzaA==",       // wrong digest label
            "pbkdf2\$sha256\$notanumber\$c2FsdA==\$aGFzaA==",
            "pbkdf2\$sha256\$600000\$!!!notbase64!!!\$aGFzaA=="
        ).forEach {
            assertFalse("must reject: $it", PasswordUtil.verify("Password123", it))
            assertTrue("must request rehash: $it", PasswordUtil.needsRehash(it))
        }
    }

    @Test fun extremeIterationRecords_areRejected() {
        // Below MIN_ITERATIONS and above MAX_ITERATIONS must both be refused, so a hostile
        // record can neither weaken verification nor be used as a CPU-exhaustion vector.
        val tooLow = hashAt("Password123", 1_000)
        val tooHigh = "pbkdf2\$sha256\$99000000\$${java.util.Base64.getEncoder().encodeToString(ByteArray(16))}\$${java.util.Base64.getEncoder().encodeToString(ByteArray(32))}"

        assertFalse("iterations below floor must be rejected", PasswordUtil.verify("Password123", tooLow))
        assertTrue(PasswordUtil.needsRehash(tooLow))
        assertFalse("iterations above ceiling must be rejected", PasswordUtil.verify("Password123", tooHigh))
        assertTrue(PasswordUtil.needsRehash(tooHigh))
    }
}
