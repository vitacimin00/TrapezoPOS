package com.trapezo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionState(
    val user: UserEntity? = null,
    val initializing: Boolean = true,
    val needsSetup: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

class AppViewModel : ViewModel() {
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        reinitialize()
    }

    /** Re-evaluates whether the app should show setup or login, against the current DB. */
    private fun reinitialize(notice: String? = null) {
        viewModelScope.launch {
            val hasUsers = AppGraph.users.hasUsers()
            val legacyDefault = hasUsers && AppGraph.users.requiresLegacyDefaultReset()
            _session.value = SessionState(
                initializing = false,
                needsSetup = !hasUsers || legacyDefault,
                error = if (legacyDefault) {
                    "Akun default lama admin/admin123 terdeteksi. Buat kredensial pemilik baru sebelum melanjutkan."
                } else null,
                notice = notice
            )
        }
    }

    fun login(username: String, password: String) = viewModelScope.launch {
        _session.value = _session.value.copy(loading = true, error = null)

        // Defense in depth for upgraded databases: never allow the universal legacy
        // credential through normal login, even if UI state is stale after a restore.
        if (AppGraph.users.requiresLegacyDefaultReset()) {
            _session.value = SessionState(
                initializing = false,
                needsSetup = true,
                error = "Akun default lama harus diamankan terlebih dahulu. Buat kredensial pemilik baru."
            )
            return@launch
        }

        AuthRepository(AppGraph.db.userDao()).login(username, password).fold(
            onSuccess = { _session.value = SessionState(user = it, initializing = false) },
            onFailure = {
                _session.value = SessionState(
                    initializing = false,
                    needsSetup = false,
                    error = it.message ?: "Login gagal"
                )
            }
        )
    }

    fun setupOwner(username: String, name: String, password: String) = viewModelScope.launch {
        _session.value = _session.value.copy(loading = true, error = null)
        val result = if (AppGraph.users.requiresLegacyDefaultReset()) {
            AppGraph.users.resetLegacyDefaultAdmin(username, name, password)
        } else {
            AppGraph.users.bootstrapAdmin(username, name, password)
        }
        if (result.error != null) {
            _session.value = SessionState(initializing = false, needsSetup = true, error = result.error)
        } else {
            _session.value = SessionState(user = result.user, initializing = false)
        }
    }

    fun logout() {
        _session.value = SessionState(initializing = false)
    }

    /**
     * Re-reads the SESSION's identity from the authoritative database. Called when the
     * logged-in user edits their own record (role/name/username/password), so navigation
     * and identity immediately follow the new role instead of a stale login snapshot.
     * Forces logout when the user no longer exists or is no longer active.
     */
    fun refreshCurrentSession() {
        val current = _session.value.user ?: return
        viewModelScope.launch {
            val fresh = AppGraph.users.byId(current.id)
            if (fresh == null || !fresh.isActive) {
                _session.value = SessionState(initializing = false)
            } else {
                _session.value = _session.value.copy(user = fresh)
            }
        }
    }

    /**
     * Reinitializes authentication after a successful restore. A restored database may
     * contain entirely different users/roles/credentials, so the previous in-memory
     * identity must never be assumed to still represent the same person.
     */
    fun forceReauthAfterRestore(message: String? = null) {
        reinitialize(notice = message ?: "Restore berhasil. Silakan masuk kembali menggunakan akun dari backup.")
    }
}
