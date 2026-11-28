package io.github.drivesync.core.work.android

import androidx.work.Data
import androidx.work.workDataOf
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.work.RewindResult

/** A finished rewind as WorkManager output data, so the Rewind screen can show how it went. */
object RewindWork {
    private const val KEY_CHANGED = "changed"
    private const val KEY_SKIPPED = "skipped"
    private const val KEY_FAILED = "failed"
    private const val KEY_REFUSED = "refused"
    private const val KEY_PROBLEM = "problem"

    fun output(result: RewindResult): Data =
        when (result) {
            is RewindResult.Done -> {
                workDataOf(KEY_CHANGED to result.changed, KEY_SKIPPED to result.skipped, KEY_FAILED to result.failed)
            }

            is RewindResult.Refused -> {
                workDataOf(KEY_REFUSED to result.reason.name)
            }

            is RewindResult.NeedsUser -> {
                workDataOf(KEY_PROBLEM to result.detail)
            }

            is RewindResult.Retry -> {
                workDataOf(KEY_PROBLEM to result.detail)
            }
        }

    fun read(data: Data): RewindResult {
        data.getString(KEY_REFUSED)?.let { name ->
            val reason = RewindRefusal.entries.firstOrNull { it.name == name } ?: RewindRefusal.POINT_GONE
            return RewindResult.Refused(reason)
        }
        data.getString(KEY_PROBLEM)?.let { return RewindResult.NeedsUser(it) }
        return RewindResult.Done(data.getInt(KEY_CHANGED, 0), data.getInt(KEY_SKIPPED, 0), data.getInt(KEY_FAILED, 0))
    }
}
