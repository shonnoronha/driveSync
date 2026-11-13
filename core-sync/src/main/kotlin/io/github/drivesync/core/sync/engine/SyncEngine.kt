package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.HashRequirements
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.PlanInput
import io.github.drivesync.core.sync.plan.PlanResult
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
    ): SyncOutcome =
        mutex.withLock {
            val base = baseStore.load()
            val remoteSnapshot = remote.scan()
            val localSnapshot = withHashes(cleanUpTempFiles(local.scan()), remoteSnapshot, base)
            val input = PlanInput(localSnapshot, remoteSnapshot, base, settings, stamp, deletionsConfirmed)
            when (val result = SyncPlanner.plan(input)) {
                is PlanResult.Paused -> {
                    SyncOutcome.Paused(result.reason)
                }

                is PlanResult.NeedsDeleteConfirmation -> {
                    SyncOutcome.NeedsDeleteConfirmation(result.deleteCount, result.pairFileCount)
                }

                is PlanResult.Ready -> {
                    val report = executor.execute(result.plan, base)
                    // An empty commit marks the base as existing after the first run (rule 7 then lifts).
                    baseStore.commit(emptyList())
                    SyncOutcome.Completed(result.plan, report)
                }
            }
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
    ): Snapshot<LocalEntry> {
        if (local !is Snapshot.Available || remote !is Snapshot.Available) return local
        val needed = HashRequirements.localPathsNeedingHash(local.entries, remote.entries, base)
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
