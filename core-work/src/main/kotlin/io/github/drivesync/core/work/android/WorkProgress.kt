package io.github.drivesync.core.work.android

import androidx.work.Data
import androidx.work.workDataOf
import io.github.drivesync.core.sync.engine.SyncProgress

/** A running sync's progress for one pair, as shown on the home screen. */
data class PairProgress(
    val pairId: String,
    /** Null while checking for changes; then the number of finished file operations. */
    val done: Int?,
    val total: Int?,
)

/** How the sync worker publishes progress through WorkManager (WorkInfo.progress). */
object WorkProgress {
    private const val KEY_PAIR_ID = "progressPairId"
    private const val KEY_DONE = "progressDone"
    private const val KEY_TOTAL = "progressTotal"
    private const val UNKNOWN = -1

    fun data(
        pairId: String,
        progress: SyncProgress,
    ): Data =
        when (progress) {
            SyncProgress.Checking -> {
                workDataOf(KEY_PAIR_ID to pairId, KEY_DONE to UNKNOWN, KEY_TOTAL to UNKNOWN)
            }

            is SyncProgress.Transferring -> {
                workDataOf(KEY_PAIR_ID to pairId, KEY_DONE to progress.done, KEY_TOTAL to progress.total)
            }
        }

    fun read(data: Data): PairProgress? {
        val pairId = data.getString(KEY_PAIR_ID) ?: return null
        val done = data.getInt(KEY_DONE, UNKNOWN).takeIf { it != UNKNOWN }
        val total = data.getInt(KEY_TOTAL, UNKNOWN).takeIf { it != UNKNOWN }
        return PairProgress(pairId, done, total)
    }
}
