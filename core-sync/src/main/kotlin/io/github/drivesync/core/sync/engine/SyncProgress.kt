package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.plan.SyncAction

/** Where a sync run is, for progress bars and notifications. */
sealed interface SyncProgress {
    /** Scanning both sides and planning; the amount of work is not known yet. */
    data object Checking : SyncProgress

    /** [done] of [total] file operations finished (succeeded, failed or skipped). */
    data class Transferring(
        val done: Int,
        val total: Int,
    ) : SyncProgress

    companion object {
        /** Base bookkeeping moves no file, so it is not counted as work the user waits for. */
        fun counts(action: SyncAction): Boolean = action !is SyncAction.Link && action !is SyncAction.ForgetBase
    }
}

/** Receives progress updates. Called on the sync coroutine; implementations should be quick. */
fun interface SyncProgressListener {
    fun onProgress(progress: SyncProgress)

    companion object {
        val NONE = SyncProgressListener { }
    }
}
