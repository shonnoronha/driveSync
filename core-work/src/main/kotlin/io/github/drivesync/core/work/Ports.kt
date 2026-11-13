package io.github.drivesync.core.work

import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import java.time.LocalDateTime

/** Builds the two sides of a pair (SAF tree and Drive folder in production). */
interface SyncStores {
    fun local(pair: SyncPair): LocalStore

    fun remote(pair: SyncPair): RemoteStore
}

/** Clock and device name, for conflict-copy names. */
interface SyncEnvironment {
    fun now(): LocalDateTime

    /** Used when the user did not set a device name. */
    val defaultDeviceName: String
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
