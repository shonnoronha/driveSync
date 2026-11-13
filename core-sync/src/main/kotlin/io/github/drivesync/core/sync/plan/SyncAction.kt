package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry

/**
 * One step for the executor. Entries carried by an action are the state the planner saw; the executor must
 * re-check them right before writing and re-plan the file if they changed (PRD 3.9 rule 4).
 */
sealed interface SyncAction {
    val path: RelPath

    data class CreateLocalFolder(
        override val path: RelPath,
    ) : SyncAction

    data class CreateRemoteFolder(
        override val path: RelPath,
    ) : SyncAction

    /** Rename/move a Drive file from [from] to [path]. */
    data class MoveRemote(
        override val path: RelPath,
        val from: RelPath,
        val remote: RemoteEntry,
        val reason: MoveReason,
    ) : SyncAction

    /**
     * Rename/move a local file from [from] to [path]. For a detected Drive move, [remote] is the moved Drive file,
     * whose new version goes into the base (Drive bumps the version on a rename).
     */
    data class MoveLocal(
        override val path: RelPath,
        val from: RelPath,
        val local: LocalEntry,
        val reason: MoveReason,
        val remote: RemoteEntry? = null,
    ) : SyncAction

    /** Upload [local] to [path]. [replaces] is the Drive file to overwrite (expected state), or null to create. */
    data class Upload(
        override val path: RelPath,
        val local: LocalEntry,
        val replaces: RemoteEntry?,
    ) : SyncAction

    /** Download [remote] to [path]. [replaces] is the local file to overwrite (expected state), or null to create. */
    data class Download(
        override val path: RelPath,
        val remote: RemoteEntry,
        val replaces: LocalEntry?,
    ) : SyncAction

    /** Both sides already match: record them as the new base without any transfer. */
    data class Link(
        override val path: RelPath,
        val local: LocalEntry,
        val remote: RemoteEntry,
    ) : SyncAction

    /** Drop the base entry (e.g. deleted on both sides, or a delete that is deliberately not propagated). */
    data class ForgetBase(
        override val path: RelPath,
    ) : SyncAction

    /** Move to Drive trash, never a permanent delete (PRD 3.9 rule 10). */
    data class TrashRemote(
        override val path: RelPath,
        val remote: RemoteEntry,
    ) : SyncAction

    /** Move to the local recycle folder, never a permanent delete (PRD 3.9 rule 10). */
    data class RecycleLocal(
        override val path: RelPath,
        val local: LocalEntry,
    ) : SyncAction
}

enum class MoveReason {
    /** The user renamed/moved the file on the other side; the base entry moves with it. */
    DETECTED_MOVE,

    /** Keep-both conflict: the losing version is renamed to its conflict-copy name. */
    CONFLICT_COPY,

    /** A same-name Drive duplicate is renamed so every file has a unique path (FR-S13). */
    DUPLICATE_RENAME,
}

internal val SyncAction.isFileDelete: Boolean
    get() =
        when (this) {
            is SyncAction.TrashRemote -> !remote.isFolder
            is SyncAction.RecycleLocal -> !local.isFolder
            else -> false
        }
