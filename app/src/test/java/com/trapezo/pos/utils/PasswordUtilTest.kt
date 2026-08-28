package com.trapezo.pos.utils

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
}
