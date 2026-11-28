package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.RemoteVersion

/** What a rewind does to one path. */
enum class RewindChangeKind {
    /** Changed since the point: the older version comes back, as a new version. */
    RESTORE,

    /** Deleted or renamed since the point: it comes back. */
    BRING_BACK,

    /** Added since the point: it goes to the Drive trash and the phone's recycle folder. */
    TO_TRASH,
}

data class RewindChange(
    val path: RelPath,
    val isFolder: Boolean,
    val kind: RewindChangeKind,
)

enum class RewindSkipReason {
    /** Changed on the phone or on Drive since the last sync. Sync first, then rewind. */
    CHANGED_SINCE_SYNC,

    /** Drive no longer has that version (past its 30 days or 100 versions, or deleted for good). */
    VERSION_GONE,

    /** A folder that still holds files the rewind keeps. */
    FOLDER_NOT_EMPTY,

    /** Something else has the name now, or several Drive files share it. */
    NAME_TAKEN,

    /** Excluded by the folder's skip rules. */
    EXCLUDED,
}

data class RewindSkip(
    val path: RelPath,
    val reason: RewindSkipReason,
)

/** Why a rewind does not run at all. */
enum class RewindRefusal {
    /** The pair never synced, so there is nothing to go back to. */
    NO_HISTORY,

    /** The chosen point is no longer in the history (pruned, or the history started over). */
    POINT_GONE,

    /** The point has no files while the folder has some: going there would only move everything to the trash. */
    EMPTY_POINT,
    LOCAL_UNAVAILABLE,
    REMOTE_UNAVAILABLE,
}

data class RewindInput(
    /** The base at the chosen point (see BaseTimeline). */
    val target: Map<RelPath, BaseEntry>,
    val base: Map<RelPath, BaseEntry>,
    val local: List<LocalEntry>,
    val remote: List<RemoteEntry>,
    /** Drive versions by file id, for [RewindPlanner.versionsNeeded]. */
    val versions: Map<String, List<RemoteVersion>>,
    val filters: FileFilters = FileFilters(),
)

data class RewindPlan(
    /** Ordered: retire files (deepest first), retire folders, create folders (shallowest first), restores. */
    val actions: List<SyncAction>,
    val skipped: List<RewindSkip>,
    val changes: List<RewindChange>,
) {
    fun asSyncPlan(): SyncPlan = SyncPlan(actions, conflicts = emptyList(), skipped = emptyList())
}

/**
 * Plans a rewind: from the base now to the base at an earlier point. Pure, like [SyncPlanner]. Safety:
 * - only paths that are in sync on both sides (matching the base) are touched; anything else is skipped;
 * - nothing is deleted for good: newer files go to the Drive trash and the phone recycle folder (PRD 3.9 rule 10);
 * - older content comes back as a new Drive version, so the rewind itself can be rewound;
 * - a folder goes only when everything in it goes too, on both sides.
 */
object RewindPlanner {
    /** What the rewind changes, from history alone (no phone or Drive access): the preview the user confirms. */
    fun preview(
        target: Map<RelPath, BaseEntry>,
        base: Map<RelPath, BaseEntry>,
    ): List<RewindChange> {
        val out = mutableListOf<RewindChange>()
        (target.keys + base.keys).sorted().forEach { path ->
            val t = target[path]
            val b = base[path]
            when {
                t == null && b != null -> {
                    out += RewindChange(path, b.isFolder, RewindChangeKind.TO_TRASH)
                }

                t != null && b == null -> {
                    out += RewindChange(path, t.isFolder, RewindChangeKind.BRING_BACK)
                }

                t != null && b != null && t.isFolder != b.isFolder -> {
                    out += RewindChange(path, b.isFolder, RewindChangeKind.TO_TRASH)
                    out += RewindChange(path, t.isFolder, RewindChangeKind.BRING_BACK)
                }

                t != null && b != null && !t.isFolder && !sameContent(t, b) -> {
                    out += RewindChange(path, false, RewindChangeKind.RESTORE)
                }
            }
        }
        return out
    }

    fun refusal(
        target: Map<RelPath, BaseEntry>,
        base: Map<RelPath, BaseEntry>,
    ): RewindRefusal? =
        if (target.values.none { !it.isFolder } && base.values.any { !it.isFolder }) RewindRefusal.EMPTY_POINT else null

    /** The Drive files whose version lists the plan needs. */
    fun versionsNeeded(
        target: Map<RelPath, BaseEntry>,
        base: Map<RelPath, BaseEntry>,
    ): Set<String> =
        preview(target, base)
            .filter { !it.isFolder && it.kind != RewindChangeKind.TO_TRASH }
            .mapNotNull { target[it.path]?.remoteFileId }
            .toSet()

    fun plan(input: RewindInput): RewindPlan = Builder(input).build()

    private class Builder(
        private val input: RewindInput,
    ) {
        private val local = input.local.associateBy { it.path }
        private val remote = input.remote.filter { !it.isGoogleNative }.groupBy { it.path }
        private val skipped = mutableListOf<RewindSkip>()
        private val retireFiles = mutableListOf<SyncAction>()
        private val folderCandidates = mutableListOf<BaseEntry>()
        private val createFolders = mutableListOf<SyncAction>()
        private val restores = mutableListOf<SyncAction>()
        private val retiring = HashSet<RelPath>()
        private val changes = preview(input.target, input.base)

        fun build(): RewindPlan {
            changes.forEach { change ->
                if (input.filters.excludesPath(change.path)) {
                    skip(change.path, RewindSkipReason.EXCLUDED)
                } else {
                    when (change.kind) {
                        RewindChangeKind.TO_TRASH -> retire(input.base.getValue(change.path))
                        RewindChangeKind.BRING_BACK -> bringBack(input.target.getValue(change.path))
                        RewindChangeKind.RESTORE -> restore(input.target.getValue(change.path))
                    }
                }
            }
            val retireFolders = retireFolders()
            val actions =
                retireFiles.sortedByDescending { it.path.depth } +
                    retireFolders +
                    createFolders.sortedBy { it.path.depth } +
                    restores.sortedBy { it.path }
            return RewindPlan(actions, skipped.distinct(), changes)
        }

        private fun retire(b: BaseEntry) {
            val path = b.path
            val drive = remote[path].orEmpty()
            val l = local[path]
            val r = drive.singleOrNull()
            when {
                b.remotePath != null || drive.size > 1 -> {
                    skip(path, RewindSkipReason.NAME_TAKEN)
                }

                !inSync(b, l, r) -> {
                    skip(path, RewindSkipReason.CHANGED_SINCE_SYNC)
                }

                b.isFolder -> {
                    folderCandidates += b
                }

                else -> {
                    retireFiles += SyncAction.TrashRemote(path, requireNotNull(r))
                    retireFiles += SyncAction.RecycleLocal(path, requireNotNull(l))
                    retiring += path
                }
            }
        }

        /** Deepest first, and only when nothing under the folder stays, on either side. */
        private fun retireFolders(): List<SyncAction> {
            val out = mutableListOf<SyncAction>()
            folderCandidates.sortedByDescending { it.path.depth }.forEach { folder ->
                val path = folder.path
                val inside =
                    input.local.map { it.path }.filter { path.isAncestorOf(it) } +
                        input.remote.map { it.path }.filter { path.isAncestorOf(it) }
                if (inside.all { it in retiring }) {
                    remote[path]?.singleOrNull()?.let { out += SyncAction.TrashRemote(path, it) }
                    local[path]?.let { out += SyncAction.RecycleLocal(path, it) }
                    retiring += path
                } else {
                    skip(path, RewindSkipReason.FOLDER_NOT_EMPTY)
                }
            }
            return out
        }

        private fun bringBack(t: BaseEntry) {
            val path = t.path
            if (t.isFolder) {
                // Both are idempotent, so a folder that is already there on one side is simply reused.
                createFolders += SyncAction.CreateRemoteFolder(path)
                createFolders += SyncAction.CreateLocalFolder(path)
                return
            }
            val occupiedNow = input.base[path]
            val free = local[path] == null && remote[path].isNullOrEmpty()
            when {
                t.remotePath != null || (occupiedNow == null && !free) -> {
                    skip(path, RewindSkipReason.NAME_TAKEN)
                }

                occupiedNow != null && path !in retiring -> {
                    skip(path, RewindSkipReason.NAME_TAKEN)
                }

                else -> {
                    addRestore(t, replacesRemote = null, replacesLocal = null)
                }
            }
        }

        private fun restore(t: BaseEntry) {
            val path = t.path
            val b = input.base.getValue(path)
            val drive = remote[path].orEmpty()
            val l = local[path]
            val r = drive.singleOrNull()
            when {
                b.remotePath != null || t.remotePath != null || drive.size > 1 -> {
                    skip(path, RewindSkipReason.NAME_TAKEN)
                }

                !inSync(b, l, r) -> {
                    skip(path, RewindSkipReason.CHANGED_SINCE_SYNC)
                }

                else -> {
                    addRestore(t, replacesRemote = r, replacesLocal = l)
                }
            }
        }

        private fun addRestore(
            t: BaseEntry,
            replacesRemote: RemoteEntry?,
            replacesLocal: LocalEntry?,
        ) {
            val fileId = t.remoteFileId
            val version = fileId?.let { pickVersion(input.versions[it].orEmpty(), t.md5) }
            if (fileId == null || version == null) {
                skip(t.path, RewindSkipReason.VERSION_GONE)
            } else {
                restores += SyncAction.Restore(t.path, fileId, version, replacesRemote, replacesLocal)
            }
        }

        private fun skip(
            path: RelPath,
            reason: RewindSkipReason,
        ) {
            skipped += RewindSkip(path, reason)
        }
    }

    /** The newest Drive version with exactly that content. */
    internal fun pickVersion(
        versions: List<RemoteVersion>,
        md5: String?,
    ): RemoteVersion? = md5?.let { wanted -> versions.filter { it.md5 == wanted }.maxByOrNull { it.modifiedTime } }

    private fun sameContent(
        a: BaseEntry,
        b: BaseEntry,
    ): Boolean =
        if (a.md5 != null && b.md5 != null) {
            a.md5 == b.md5
        } else {
            a.remoteFileId == b.remoteFileId && a.remoteVersion == b.remoteVersion
        }

    /** Both sides still match the base (the same test the sync planner uses for "unchanged"). */
    private fun inSync(
        b: BaseEntry,
        l: LocalEntry?,
        r: RemoteEntry?,
    ): Boolean {
        if (l == null || r == null) return false
        if (l.isFolder != b.isFolder || r.isFolder != b.isFolder) return false
        if (b.isFolder) return true
        val localSame = l.size == b.localSize && l.lastModified == b.localLastModified
        val remoteSame =
            r.fileId == b.remoteFileId &&
                if (r.md5 != null && b.md5 != null) r.md5 == b.md5 else r.version == b.remoteVersion
        return localSame && remoteSame
    }
}
