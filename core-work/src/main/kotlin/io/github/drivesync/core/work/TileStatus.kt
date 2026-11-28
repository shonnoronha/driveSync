package io.github.drivesync.core.work

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.SyncPair

/** What the Quick Settings tile shows (FR-M4), worked out from the pairs alone so it is testable. */
enum class TileStatus {
    /** Not signed in, or no folders yet: tapping opens the app. */
    SET_UP,
    SYNCING,

    /** A folder is paused on something only the user can fix (sign-in, missing folder, deletes to confirm). */
    NEEDS_YOU,

    /** Every folder is paused by the user. */
    PAUSED,

    /** The last run left files that failed; they are retried on the next run. */
    FAILED,
    IDLE,
    ;

    /** Tapping starts a sync; otherwise it opens the app, because the user has something to do there first. */
    val tapSyncs: Boolean get() = this == SYNCING || this == FAILED || this == IDLE

    companion object {
        fun of(
            signedIn: Boolean,
            pairs: List<SyncPair>,
            syncing: Boolean,
        ): TileStatus {
            val active = pairs.filter { it.enabled }
            return when {
                !signedIn || pairs.isEmpty() -> SET_UP
                syncing -> SYNCING
                active.any { it.status in USER_FIXES } -> NEEDS_YOU
                active.isEmpty() -> PAUSED
                active.any { it.status == PairStatus.ERROR } -> FAILED
                else -> IDLE
            }
        }

        /** When every active folder had synced at least once: the oldest of their last syncs, else null. */
        fun allSyncedSince(pairs: List<SyncPair>): Long? {
            val times = pairs.filter { it.enabled }.map { it.lastSyncAt }
            if (times.isEmpty() || times.any { it == null }) return null
            return times.filterNotNull().min()
        }
    }
}

private val USER_FIXES = setOf(PairStatus.NEEDS_ATTENTION, PairStatus.WAITING_FOR_CONFIRMATION)
