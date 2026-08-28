package com.trapezo.pos.data.repository

import com.trapezo.pos.data.entity.UserEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationTest {

    private fun actor(role: String, active: Boolean, id: Long = 1) =
        UserEntity(id = id, username = "u$id", passwordHash = "x", name = "U$id", role = role, isActive = active)

    @Test fun missingActor_isDenied() {
        assertEquals("Akun tidak ditemukan", Authorization.denyReason(null))
    }

    @Test fun inactiveAdmin_isDenied() {
        assertEquals("Akun tidak aktif", Authorization.denyReason(actor("ADMIN", active = false)))
    }

    @Test fun cashier_isDenied() {
        assertEquals("Hanya admin aktif yang dapat melakukan aksi ini", Authorization.denyReason(actor("CASHIER", active = true)))
    }

    @Test fun activeAdmin_isAllowed() {
        assertNull(Authorization.denyReason(actor("ADMIN", active = true)))
    }

    @Test fun inactiveCashier_isDeniedAsInactive() {
        assertEquals("Akun tidak aktif", Authorization.denyReason(actor("CASHIER", active = false)))
    }
}
