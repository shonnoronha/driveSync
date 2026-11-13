package io.github.drivesync.core.data.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ActivityKind
import io.github.drivesync.core.data.db.ConflictEntity
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.db.SkippedEntity
import io.github.drivesync.core.sync.engine.ActionOutcome
import io.github.drivesync.core.sync.engine.ActionResult
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.plan.SyncPlan
import javax.inject.Inject

/**
 * Turns the result of a sync run into the pair's status, its activity log (FR-M2) and its conflict and skipped
 * lists. Status changes are logged once, not on every run, so a paused pair does not flood the log.
 */
class SyncRecorder(
    private val db: DriveSyncDatabase,
    private val clock: () -> Long,
) {
    @Inject
    constructor(db: DriveSyncDatabase) : this(db, System::currentTimeMillis)

    suspend fun record(
        pairId: String,
        outcome: SyncOutcome,
    ) {
        val now = clock()
        when (outcome) {
            is SyncOutcome.Paused -> {
                setStatus(pairId, PairStatus.NEEDS_ATTENTION, outcome.reason.describe(), ActivityKind.PAUSED)
            }

            is SyncOutcome.NeedsDeleteConfirmation -> {
                val detail = "${outcome.deleteCount} of ${outcome.pairFileCount} files would be deleted"
                setStatus(pairId, PairStatus.WAITING_FOR_CONFIRMATION, detail, ActivityKind.WAITING_FOR_CONFIRMATION)
            }

            is SyncOutcome.Completed -> {
                completed(pairId, outcome, now)
            }
        }
    }

    /**
     * A run that could not even start or finish: auth revoked, Drive full, network down. [needsUser] marks the pair
     * "needs attention"; otherwise the pair keeps its status and the next run retries.
     */
    suspend fun recordFailure(
        pairId: String,
        message: String,
        needsUser: Boolean,
    ) {
        if (needsUser) {
            setStatus(pairId, PairStatus.NEEDS_ATTENTION, message, ActivityKind.PAUSED)
        } else {
            db.activity().insert(
                listOf(ActivityEntity(pairId = pairId, time = clock(), kind = ActivityKind.FAILED, detail = message)),
            )
        }
    }

    private suspend fun completed(
        pairId: String,
        outcome: SyncOutcome.Completed,
        now: Long,
    ) {
        val entries =
            outcome.report.results.mapNotNull { it.toActivity(pairId, now) } +
                conflictEntries(pairId, outcome.plan, now)
        db.activity().insert(entries)
        db.pairItems().replace(
            pairId,
            outcome.plan.conflicts.map {
                ConflictEntity(pairId, it.path.value, it.kind, it.resolution, it.conflictCopy?.value, now)
            },
            outcome.plan.skipped.map { SkippedEntity(pairId, it.path.value, it.reason) },
        )
        val failed = outcome.report.results.count { it.outcome is ActionOutcome.Failed }
        if (failed > 0) {
            db.pairs().setStatus(pairId, PairStatus.ERROR, "$failed files failed; they are retried on the next run")
        } else {
            db.pairs().setStatus(pairId, PairStatus.IDLE, null)
        }
        db.pairs().setLastSync(pairId, now)
    }

    private suspend fun setStatus(
        pairId: String,
        status: PairStatus,
        detail: String,
        kind: ActivityKind,
    ) {
        val current = db.pairs().get(pairId) ?: return
        if (current.status == status && current.statusDetail == detail) return
        db.pairs().setStatus(pairId, status, detail)
        db.activity().insert(listOf(ActivityEntity(pairId = pairId, time = clock(), kind = kind, detail = detail)))
    }

    private fun conflictEntries(
        pairId: String,
        plan: SyncPlan,
        now: Long,
    ) = plan.conflicts.map {
        val detail =
            "${it.kind.name.lowercase()}: ${it.resolution.name.lowercase()}" +
                it.conflictCopy?.let { c -> " → $c" }.orEmpty()
        ActivityEntity(pairId = pairId, time = now, kind = ActivityKind.CONFLICT, path = it.path.value, detail = detail)
    }

    private fun ActionResult.toActivity(
        pairId: String,
        now: Long,
    ): ActivityEntity? {
        val path = action.path.value
        return when (val result = outcome) {
            is ActionOutcome.Failed -> {
                ActivityEntity(
                    pairId = pairId,
                    time = now,
                    kind = ActivityKind.FAILED,
                    path = path,
                    detail = result.message,
                )
            }

            ActionOutcome.Done -> {
                action.doneKind()?.let { (kind, detail) ->
                    ActivityEntity(
                        pairId = pairId,
                        time = now,
                        kind = kind,
                        path = path,
                        detail = detail,
                    )
                }
            }

            // Re-planned and blocked actions are retried by the next run; logging them would only add noise.
            is ActionOutcome.Replan, is ActionOutcome.Skipped -> {
                null
            }
        }
    }

    private fun SyncAction.doneKind(): Pair<ActivityKind, String?>? =
        when (this) {
            is SyncAction.Upload -> ActivityKind.UPLOADED to null

            is SyncAction.Download -> ActivityKind.DOWNLOADED to null

            is SyncAction.MoveRemote -> movedKind(reason, from.value)

            is SyncAction.MoveLocal -> movedKind(reason, from.value)

            is SyncAction.CreateLocalFolder, is SyncAction.CreateRemoteFolder -> ActivityKind.FOLDER_CREATED to null

            is SyncAction.TrashRemote -> ActivityKind.TRASHED_ON_DRIVE to null

            is SyncAction.RecycleLocal -> ActivityKind.RECYCLED_ON_PHONE to null

            // Base bookkeeping only.
            is SyncAction.Link, is SyncAction.ForgetBase -> null
        }

    private fun movedKind(
        reason: MoveReason,
        from: String,
    ): Pair<ActivityKind, String?> =
        ActivityKind.MOVED to
            "from $from" + if (reason == MoveReason.DETECTED_MOVE) "" else " (${reason.name.lowercase()})"

    private fun PauseReason.describe(): String =
        when (this) {
            is PauseReason.LocalUnreachable -> "The phone folder can't be read: $detail"
            is PauseReason.RemoteUnreachable -> "The Drive folder can't be reached: $detail"
            PauseReason.LocalSuddenlyEmpty -> "The phone folder is suddenly empty"
            PauseReason.RemoteSuddenlyEmpty -> "The Drive folder is suddenly empty"
        }
}
