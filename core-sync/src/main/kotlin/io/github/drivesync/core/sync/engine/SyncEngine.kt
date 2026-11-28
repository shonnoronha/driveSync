package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.HashRequirements
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.PlanInput
import io.github.drivesync.core.sync.plan.PlanResult
import io.github.drivesync.core.sync.plan.RewindInput
import io.github.drivesync.core.sync.plan.RewindPlan
import io.github.drivesync.core.sync.plan.RewindPlanner
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.sync.plan.SyncPlan
import io.github.drivesync.core.sync.plan.SyncPlanner
import io.github.drivesync.core.sync.store.BaseStore
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One pair's sync pipeline (PRD 7): scan → lazy hash → plan → safety check → execute (checkpointed) → report.
 * A per-pair mutex guarantees only one run at a time.
 */
class SyncEngine(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val baseStore: BaseStore,
) {
    private val mutex = Mutex()
    private val executor = SyncExecutor(local, remote, baseStore)

    suspend fun sync(
        settings: PairSettings,
        stamp: ConflictStamp,
        deletionsConfirmed: Boolean = false,
        resolutions: Map<RelPath, ConflictPolicy> = emptyMap(),
        progress: SyncProgressListener = SyncProgressListener.NONE,
    ): SyncOutcome =
        mutex.withLock {
            progress.onProgress(SyncProgress.Checking)
            val base = baseStore.load()
            val remoteSnapshot = remote.scan()
            val localSnapshot = withHashes(cleanUpTempFiles(local.scan()), remoteSnapshot, base, settings)
            val input = PlanInput(localSnapshot, remoteSnapshot, base, settings, stamp, deletionsConfirmed, resolutions)
            when (val result = SyncPlanner.plan(input)) {
                is PlanResult.Paused -> {
                    SyncOutcome.Paused(result.reason)
                }

                is PlanResult.NeedsDeleteConfirmation -> {
                    SyncOutcome.NeedsDeleteConfirmation(result.deleteCount, result.pairFileCount)
                }

                is PlanResult.Ready -> {
                    val report = executor.execute(result.plan, base, progress)
                    // An empty commit marks the base as existing after the first run (rule 7 then lifts).
                    baseStore.commit(emptyList())
                    SyncOutcome.Completed(result.plan, report)
                }
            }
        }

    /**
     * Rewind: puts the folder back the way it was at an earlier point of its history. [target] is the base at that
     * point (from a BaseTimeline). Runs under the same lock as [sync]; see [RewindPlanner] for the safety rules.
     */
    suspend fun rewind(
        target: Map<RelPath, BaseEntry>,
        settings: PairSettings,
        progress: SyncProgressListener = SyncProgressListener.NONE,
    ): RewindOutcome =
        mutex.withLock {
            progress.onProgress(SyncProgress.Checking)
            val base = baseStore.load() ?: return@withLock RewindOutcome.Refused(RewindRefusal.NO_HISTORY)
            val current = base.entries.associateBy { it.path }
            RewindPlanner.refusal(target, current)?.let { return@withLock RewindOutcome.Refused(it) }
            val remoteSnapshot = remote.scan()
            val localSnapshot = cleanUpTempFiles(local.scan())
            if (remoteSnapshot !is Snapshot.Available) {
                return@withLock RewindOutcome.Refused(RewindRefusal.REMOTE_UNAVAILABLE)
            }
            if (localSnapshot !is Snapshot.Available) {
                return@withLock RewindOutcome.Refused(RewindRefusal.LOCAL_UNAVAILABLE)
            }
            val versions = RewindPlanner.versionsNeeded(target, current).associateWith { remote.versions(it) }
            val input =
                RewindInput(
                    target,
                    current,
                    localSnapshot.entries,
                    remoteSnapshot.entries,
                    versions,
                    settings.filters,
                )
            val plan = RewindPlanner.plan(input)
            RewindOutcome.Completed(plan, executor.execute(plan.asSyncPlan(), base, progress))
        }

    /** PRD 3.9 rule 6: half-written downloads from an interrupted run are deleted on the next run. */
    private suspend fun cleanUpTempFiles(snapshot: Snapshot<LocalEntry>): Snapshot<LocalEntry> {
        if (snapshot !is Snapshot.Available) return snapshot
        val (temps, rest) =
            snapshot.entries.partition { !it.isFolder && it.path.name.endsWith(IgnoreRules.TEMP_SUFFIX) }
        temps.forEach { local.deleteTemp(it.path) }
        return if (temps.isEmpty()) snapshot else Snapshot.Available(rest)
    }

    private suspend fun withHashes(
        local: Snapshot<LocalEntry>,
        remote: Snapshot<RemoteEntry>,
        base: BaseSnapshot?,
        settings: PairSettings,
    ): Snapshot<LocalEntry> {
        if (local !is Snapshot.Available || remote !is Snapshot.Available) return local
        // Files the user excluded are never planned, so never worth reading (a skipped 4 GB video stays unread).
        val excluded = settings.filters.excludedPaths(local.entries, remote.entries)
        val needed =
            HashRequirements.localPathsNeedingHash(local.entries, remote.entries, base) - excluded
        if (needed.isEmpty()) return local
        return Snapshot.Available(
            local.entries.map { entry ->
                if (entry.path in
                    needed
                ) {
                    entry.copy(md5 = this.local.md5(entry.path))
                } else {
                    entry
                }
            },
        )
    }
}

sealed interface SyncOutcome {
    data class Paused(
        val reason: PauseReason,
    ) : SyncOutcome

    data class NeedsDeleteConfirmation(
        val deleteCount: Int,
        val pairFileCount: Int,
    ) : SyncOutcome

    data class Completed(
        val plan: SyncPlan,
        val report: ExecutionReport,
    ) : SyncOutcome
}

sealed interface RewindOutcome {
    data class Refused(
        val reason: RewindRefusal,
    ) : RewindOutcome

    data class Completed(
        val plan: RewindPlan,
        val report: ExecutionReport,
    ) : RewindOutcome
}
