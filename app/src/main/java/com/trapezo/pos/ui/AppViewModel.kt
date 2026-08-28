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

data class SessionState(val user: UserEntity? = null, val loading: Boolean = false, val error: String? = null)

class AppViewModel : ViewModel() {
    private val auth = AuthRepository(AppGraph.db.userDao())
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    fun login(username: String, password: String) = viewModelScope.launch {
        _session.value = SessionState(loading = true)
        auth.login(username, password).fold(
            onSuccess = { _session.value = SessionState(user = it) },
            onFailure = { _session.value = SessionState(error = it.message ?: "Login gagal") }
        )
    }
    fun logout() { _session.value = SessionState() }
}
