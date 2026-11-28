package io.github.drivesync.core.work

import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.history.HistoryRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.engine.SyncProgress
import io.github.drivesync.core.sync.plan.RewindRefusal
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
        private val history: HistoryRepository,
    ) {
        /**
         * Syncs every enabled pair matching [filter]. Automatic runs skip folders whose own Wi-Fi or charging rule
         * is not met right now (FR-P4); a [manual] "sync all now" includes them. Returns true when a transient
         * failure asks for a retry.
         */
        suspend fun syncAll(
            filter: (SyncPair) -> Boolean = { true },
            onProgress: PairProgressListener = PairProgressListener.NONE,
            manual: Boolean = false,
        ): Boolean {
            val app = settings.current()
            if (app.accountEmail == null) return false // Not signed in: nothing to sync yet.
            val due = pairs.pairs().filter { it.enabled && filter(it) && (manual || conditionsMet(it)) }
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

        /** Rewind, on the user's request (see [PairSyncRunner.rewind]). */
        suspend fun rewind(
            pairId: String,
            point: Long,
            onProgress: PairProgressListener = PairProgressListener.NONE,
        ): RewindResult {
            if (settings.current().accountEmail == null) return RewindResult.Refused(RewindRefusal.NO_HISTORY)
            val pair = pairs.pair(pairId) ?: return RewindResult.Refused(RewindRefusal.NO_HISTORY)
            return runner.rewind(pairId, point) { onProgress.onProgress(pair, it) }
        }

        private fun conditionsMet(pair: SyncPair): Boolean {
            val conditions = pair.settings.conditions
            if (!conditions.wifiOnly && !conditions.chargingOnly) return true
            return conditions.allow(environment.unmeteredNetwork(), environment.charging())
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
            // Drive keeps old versions for about 30 days, so Rewind points older than that rarely work.
            history.prune(before = now - TimeUnit.DAYS.toMillis(HISTORY_DAYS))
        }
    }

private const val HISTORY_DAYS = 30L

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
