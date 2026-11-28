package io.github.drivesync.core.work

import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import java.time.LocalDateTime

/** Builds the two sides of a pair (SAF tree and Drive folder in production). */
interface SyncStores {
    fun local(pair: SyncPair): LocalStore

    suspend fun remote(pair: SyncPair): RemoteStore
}

/** Clock and device name, for conflict-copy names. */
interface SyncEnvironment {
    fun now(): LocalDateTime

    /** Used when the user did not set a device name. */
    val defaultDeviceName: String

    /** True on Wi-Fi or another unmetered network, for Wi-Fi-only folders (FR-P4). */
    fun unmeteredNetwork(): Boolean

    /** True while plugged in, for charging-only folders (FR-P4). */
    fun charging(): Boolean
}

/** User-facing notifications (FR-M3). */
interface SyncNotifier {
    /** A pair paused, or waits for the user to confirm deletes. */
    fun needsAttention(
        pair: SyncPair,
        detail: String,
    )

    /** New conflicts were found in this round. */
    fun conflicts(count: Int)

    /** Some files failed and are retried later. */
    fun failures(
        pair: SyncPair,
        count: Int,
    )
}

/** Starting and stopping sync runs (WorkManager in production). */
interface SyncRuns {
    /** Cancels queued and running syncs and returns once none is running any more (or a timeout passed). */
    suspend fun stopAll()

    /** Restores the periodic schedule and starts a run of every pair now. */
    fun restart(settings: AppSettings)
}
