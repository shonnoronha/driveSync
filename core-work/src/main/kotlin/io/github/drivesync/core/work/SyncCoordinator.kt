package io.github.drivesync.core.work

import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.engine.SyncProgress
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The work behind the one global coordinator worker (PRD 6.1): syncs the due pairs one after another, notifies,
 * and prunes the activity log.
 */
class SyncCoordinator
    @Inject
    constructor(
        private val pairs: PairRepository,
        private val settings: SettingsRepository,
        private val runner: PairSyncRunner,
        private val notifier: SyncNotifier,
        private val activity: ActivityRepository,
        private val environment: SyncEnvironment,
    ) {
        /** Syncs every enabled pair matching [filter]. Returns true when a transient failure asks for a retry. */
        suspend fun syncAll(
            filter: (SyncPair) -> Boolean = { true },
            onProgress: PairProgressListener = PairProgressListener.NONE,
        ): Boolean {
            val app = settings.current()
            if (app.accountEmail == null) return false // Not signed in: nothing to sync yet.
            val due = pairs.pairs().filter { it.enabled && filter(it) }
            val results =
                due.map { pair ->
                    pair to
                        runner.run(pair.id, app.deviceName) { onProgress.onProgress(pair, it) }
                }
            notify(results)
            pruneLog(app.logRetentionDays)
            return results.any { it.second is RunResult.Retry }
        }

        /** "Sync now" for one pair, or the run after the user confirmed a mass delete (FR-S6). */
        suspend fun syncPair(
            pairId: String,
            deletionsConfirmed: Boolean,
            onProgress: PairProgressListener = PairProgressListener.NONE,
        ): Boolean {
            val app = settings.current()
            if (app.accountEmail == null) return false
            val pair = pairs.pair(pairId) ?: return false
            val result = runner.run(pairId, app.deviceName, deletionsConfirmed) { onProgress.onProgress(pair, it) }
            notify(listOf(pair to result))
            return result is RunResult.Retry
        }

        private fun notify(results: List<Pair<SyncPair, RunResult>>) {
            results.forEach { (pair, result) ->
                when (result) {
                    // Only a change of state is news; a pair that stays paused does not notify every run.
                    is RunResult.NeedsUser -> {
                        if (pair.statusDetail !=
                            result.detail
                        ) {
                            notifier.needsAttention(pair, result.detail)
                        }
                    }

                    is RunResult.Completed -> {
                        if (result.failed > 0) notifier.failures(pair, result.failed)
                    }

                    RunResult.Skipped, is RunResult.Retry -> {
                        // Nothing to tell the user: skipped pairs are off, and retries are silent.
                    }
                }
            }
            val conflicts = results.sumOf { (it.second as? RunResult.Completed)?.newConflicts ?: 0 }
            if (conflicts > 0) notifier.conflicts(conflicts)
        }

        private suspend fun pruneLog(retentionDays: Int) {
            val now =
                environment
                    .now()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            activity.prune(before = now - TimeUnit.DAYS.toMillis(retentionDays.toLong()))
        }
    }

/** Progress of the pair being synced right now. */
fun interface PairProgressListener {
    fun onProgress(
        pair: SyncPair,
        progress: SyncProgress,
    )

    companion object {
        val NONE = PairProgressListener { _, _ -> }
    }
}
