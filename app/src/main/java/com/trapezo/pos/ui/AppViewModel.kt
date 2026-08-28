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
    val error: String? = null
)

class AppViewModel : ViewModel() {
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        viewModelScope.launch {
            val hasUsers = AppGraph.users.hasUsers()
            val legacyDefault = hasUsers && AppGraph.users.requiresLegacyDefaultReset()
            _session.value = SessionState(
                initializing = false,
                needsSetup = !hasUsers || legacyDefault,
                error = if (legacyDefault) {
                    "Akun default lama admin/admin123 terdeteksi. Buat kredensial pemilik baru sebelum melanjutkan."
                } else null
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
}
