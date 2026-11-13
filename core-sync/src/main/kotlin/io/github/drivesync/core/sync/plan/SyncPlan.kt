package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import java.time.LocalDateTime

/** Everything the planner needs. The planner is a pure function of this value. */
data class PlanInput(
    val local: Snapshot<LocalEntry>,
    val remote: Snapshot<RemoteEntry>,
    /** `null` = no base at all (first sync, reinstall, cleared data): merge only, never delete (PRD 3.9 rule 7). */
    val base: BaseSnapshot?,
    val settings: PairSettings,
    val conflictStamp: ConflictStamp,
    /** Set after the user confirmed a mass delete for this run. */
    val deletionsConfirmed: Boolean = false,
    /** The user's per-file answers to pending conflicts; they override the pair's policy for those paths. */
    val resolutions: Map<RelPath, ConflictPolicy> = emptyMap(),
)

/** Used to name conflict copies: `report (conflict 2026-11-12 14-30 Pixel).docx`. */
data class ConflictStamp(
    val time: LocalDateTime,
    val deviceName: String,
)

sealed interface PlanResult {
    /** Nothing may run; the pair needs attention (PRD 3.9 rule 1). */
    data class Paused(
        val reason: PauseReason,
    ) : PlanResult

    /** The mass-delete guard tripped (FR-S6). Run [plan] only after the user confirms. */
    data class NeedsDeleteConfirmation(
        val plan: SyncPlan,
        val deleteCount: Int,
        val pairFileCount: Int,
    ) : PlanResult

    data class Ready(
        val plan: SyncPlan,
    ) : PlanResult
}

sealed interface PauseReason {
    data class LocalUnreachable(
        val detail: String,
    ) : PauseReason

    data class RemoteUnreachable(
        val detail: String,
    ) : PauseReason

    /** The local side has no files although the base has some. */
    data object LocalSuddenlyEmpty : PauseReason

    /** The Drive side has no files although the base has some. */
    data object RemoteSuddenlyEmpty : PauseReason
}

data class SyncPlan(
    /** Ordered: folder creation, moves, uploads, downloads, base bookkeeping, then deletes (deepest first). */
    val actions: List<SyncAction>,
    val conflicts: List<Conflict>,
    val skipped: List<Skipped>,
) {
    val deleteCount: Int get() = actions.count { it.isFileDelete }
}

data class Conflict(
    val path: RelPath,
    val kind: ConflictKind,
    val resolution: ConflictResolution,
    /** Where the losing version is kept, for KEPT_BOTH. */
    val conflictCopy: RelPath? = null,
)

enum class ConflictKind {
    BOTH_MODIFIED,
    BOTH_NEW,
    LOCAL_MODIFIED_REMOTE_DELETED,
    LOCAL_DELETED_REMOTE_MODIFIED,

    /** One-way pair: the destination side changed a file that the source side still has. */
    DESTINATION_MODIFIED,

    /** Two files with the same name in one Drive folder (FR-S13). */
    DUPLICATE_REMOTE_NAME,

    /** A file on one side and a folder on the other side (or in the base) at the same path. */
    TYPE_MISMATCH,
}

enum class ConflictResolution {
    KEPT_BOTH,
    LOCAL_WON,
    REMOTE_WON,

    /** Modify/delete conflict: the modified version is kept and copied back (PRD 3.3). */
    KEPT_MODIFIED,

    /** Waiting for the user (ASK policy, or no safe automatic resolution). No actions were planned. */
    PENDING,
}

data class Skipped(
    val path: RelPath,
    val reason: SkipReason,
)

enum class SkipReason {
    /** Docs, Sheets, Slides, shortcuts (FR-S12). */
    GOOGLE_NATIVE,

    /** Upload-only pair: an extra same-name Drive file is left alone. */
    DUPLICATE_REMOTE_NAME,
}
