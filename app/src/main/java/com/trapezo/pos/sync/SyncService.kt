package com.trapezo.pos.sync

/**
 * Extension point for a future cloud synchronisation implementation.
 * The offline Room database remains the source of truth; no network API is
 * hard-coded in Trapezo POS.
 */
interface SyncService {
    suspend fun syncNow(): SyncResult
    suspend fun hasPendingChanges(): Boolean
}

sealed class SyncResult {
    data object Disabled : SyncResult()
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Failure(val message: String, val retryable: Boolean = true) : SyncResult()
}

/** Default safe implementation until the store explicitly configures cloud sync. */
object OfflineOnlySyncService : SyncService {
    override suspend fun syncNow(): SyncResult = SyncResult.Disabled
    override suspend fun hasPendingChanges(): Boolean = false
}
