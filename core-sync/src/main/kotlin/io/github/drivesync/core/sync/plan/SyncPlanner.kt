package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot

/**
 * The three-way planner (PRD 3.3, 3.9): a pure function from (local now, remote now, base) to a plan.
 *
 * Safety rules enforced here:
 * 1. An unreachable or suddenly empty side pauses the pair; it never produces deletes.
 * 3. Each side is compared only with its own base values; timestamps cross sides only for NEWEST_WINS.
 * 6. Temp/ignored files, and files the user's rules exclude (FR-P5), are invisible to the planner.
 * 7. Without a base the planner only merges (link / copy / keep-both) and never deletes.
 * 8. One-way pairs propagate deletes only with "mirror deletions".
 * 9. The mass-delete guard asks before deleting > 20 files and > 10% of the pair.
 * 10. Deletes are always trash / recycle actions.
 * Rules 2, 4, 5 and 6 (verify + rename) are executor duties; actions carry the expected state for rule 4.
 */
object SyncPlanner {
    fun plan(input: PlanInput): PlanResult {
        val local = input.local
        val remote = input.remote
        return when {
            local is Snapshot.Unavailable -> PlanResult.Paused(PauseReason.LocalUnreachable(local.reason))
            remote is Snapshot.Unavailable -> PlanResult.Paused(PauseReason.RemoteUnreachable(remote.reason))
            local is Snapshot.Available && remote is Snapshot.Available -> planAvailable(input, local, remote)
            else -> error("unreachable")
        }
    }

    private fun planAvailable(
        original: PlanInput,
        localSnapshot: Snapshot.Available<LocalEntry>,
        remoteSnapshot: Snapshot.Available<RemoteEntry>,
    ): PlanResult {
        val (localIgnored, localAll) = localSnapshot.entries.partition { IgnoreRules.isIgnored(it.path) }
        val (remoteIgnored, remoteVisible) = remoteSnapshot.entries.partition { IgnoreRules.isIgnored(it.path) }
        // FR-P5: a user rule hides a path on both sides and in the base, so it can never turn into a delete.
        val excluded =
            original.settings.filters.excludedPaths(localAll, remoteVisible, original.base?.entries.orEmpty())
        val (localExcluded, local) = localAll.partition { it.path in excluded }
        val (remoteExcluded, remoteAll) = remoteVisible.partition { it.path in excluded }
        val input =
            if (excluded.isEmpty()) {
                original
            } else {
                val base = original.base?.let { b -> BaseSnapshot(b.entries.filter { it.path !in excluded }) }
                original.copy(base = base)
            }
        val (googleNative, remote) = remoteAll.partition { it.isGoogleNative }
        suddenlyEmpty(input, local, remote)?.let { return PlanResult.Paused(it) }

        val plan =
            PlanBuilder(
                local = local,
                remote = remote,
                input = input,
                preSkipped = googleNative.map { Skipped(it.path, SkipReason.GOOGLE_NATIVE) },
                localUntracked = (localIgnored + localExcluded).map { it.path },
                remoteUntracked = (remoteIgnored + remoteExcluded + googleNative).map { it.path },
            ).build()

        // Rule 7, belt and braces: a run without a base never deletes anything.
        val hasDeletes = plan.actions.any { it is SyncAction.TrashRemote || it is SyncAction.RecycleLocal }
        check(input.base != null || !hasDeletes) { "Planner produced deletes without a base snapshot" }

        val pairFileCount =
            input.base
                ?.entries
                .orEmpty()
                .count { !it.isFolder }
        val guard = input.settings.massDeleteGuard
        val deletes = plan.deleteCount
        val tripsGuard = deletes > guard.minFiles && deletes > guard.minFraction * pairFileCount
        return if (tripsGuard && !input.deletionsConfirmed) {
            PlanResult.NeedsDeleteConfirmation(plan, deletes, pairFileCount)
        } else {
            PlanResult.Ready(plan)
        }
    }

    private fun suddenlyEmpty(
        input: PlanInput,
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
    ): PauseReason? {
        val baseHasFiles =
            input.base
                ?.entries
                .orEmpty()
                .any { !it.isFolder }
        return when {
            !baseHasFiles -> null
            local.none { !it.isFolder } -> PauseReason.LocalSuddenlyEmpty
            remote.none { !it.isFolder } -> PauseReason.RemoteSuddenlyEmpty
            else -> null
        }
    }
}
