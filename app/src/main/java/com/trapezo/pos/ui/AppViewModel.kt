package com.trapezo.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trapezo.pos.AppGraph
import com.trapezo.pos.data.entity.UserEntity
import com.trapezo.pos.data.repository.AuthRepository
import com.trapezo.pos.data.database.AppDatabase
import kotlinx.coroutines.CancellationException
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
    val notice: String? = null,
    /**
     * Set only when the local database could not be opened/read/migrated at startup.
     * Drives a dedicated recovery surface instead of MainShell/Login. Normal login
     * validation failures use [error] and are NEVER fatal.
     */
    val fatalStartupError: String? = null
)

class AppViewModel : ViewModel() {
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        reinitialize()
    }

    /**
     * Re-evaluates whether the app should show setup or login, against the current DB.
     *
     * A failure to open/read/migrate the local database is a FATAL STARTUP condition: the app
     * must offer recovery rather than crash or spin on the splash forever.
     */
    private fun reinitialize(notice: String? = null) {
        _session.value = SessionState(initializing = true, notice = notice)
        viewModelScope.launch { runGuardedInitialization(notice) }
    }

    /**
     * The ONE guarded database-initialization step, shared by first startup, retry, recovery and
     * post-restore reauthentication.
     *
     * Contract:
     *  - success            -> setup/login state derived from the authoritative DB
     *  - CancellationException -> rethrown, never converted into a fatal startup error
     *  - Exception          -> fatal startup recovery state (no stack trace shown to the user)
     *  - Error (OOM, LinkageError, ...) -> NOT caught; those are not "database unreadable"
     *
     * The caller is responsible for having already published `initializing = true` and, for the
     * post-restore path, for having cleared the previous session user synchronously.
     */
    private suspend fun runGuardedInitialization(notice: String?) {
        try {
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
        } catch (e: CancellationException) {
            // Structured concurrency: never swallow cancellation.
            throw e
        } catch (e: Exception) {
            // Database could not be opened. Never expose a stack trace to the user.
            _session.value = SessionState(
                user = null,
                initializing = false,
                fatalStartupError = "Database lokal tidak dapat dibaca."
            )
        }
    }

    /** Retry action for the startup recovery surface. */
    fun retryStartup() {
        AppDatabase.closeAndClear()
        reinitialize()
    }

    /**
     * Called after a recovery restore succeeded. Rebinds against the restored database and
     * requires authentication — an old session is never resumed.
     */
    fun reinitializeAfterRecovery(message: String? = null) {
        AppDatabase.closeAndClear()
        reinitialize(notice = message)
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
     *
     * The previous identity is dropped SYNCHRONOUSLY, before the restored database is read,
     * so there is never a frame in which the restored DB is active while the old
     * authenticated user is still authoritative in [SessionState].
     */
    fun forceReauthAfterRestore(message: String? = null) {
        val notice = message ?: "Restore berhasil. Silakan masuk kembali menggunakan akun dari backup."
        // Synchronous: user == null the instant reauthentication begins. This must happen before
        // any restored-database access, so there is never a frame where the restored DB is live
        // while the previous authenticated identity is still authoritative.
        _session.value = SessionState(user = null, initializing = true, notice = notice)
        // Rebind Room against the restored files, then use the SAME guarded initialization as
        // startup: if the restored database cannot be opened, the fatal recovery surface is shown
        // instead of resuming the old session or hanging on `initializing`.
        AppDatabase.closeAndClear()
        viewModelScope.launch { runGuardedInitialization(notice) }
    }
}
