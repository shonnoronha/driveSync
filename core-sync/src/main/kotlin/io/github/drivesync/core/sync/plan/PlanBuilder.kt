package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.SyncAction.CreateLocalFolder
import io.github.drivesync.core.sync.plan.SyncAction.CreateRemoteFolder
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.ForgetBase
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.MoveLocal
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload

/** How one side of a file compares with its own base value. */
internal enum class Change {
    /** No base entry and not present. */
    ABSENT,

    /** No base entry, present now. */
    NEW,
    UNCHANGED,
    MODIFIED,

    /** Base entry exists, not present now. */
    DELETED,
    ;

    val isPresentChange: Boolean get() = this == NEW || this == MODIFIED
    val isQuiet: Boolean get() = this == UNCHANGED || this == ABSENT
}

internal fun localChange(
    local: LocalEntry?,
    base: BaseEntry?,
): Change =
    when {
        base == null -> if (local == null) Change.ABSENT else Change.NEW

        local == null -> Change.DELETED

        local.size == base.localSize && local.lastModified == base.localLastModified -> Change.UNCHANGED

        // Metadata changed but content did not (touched file).
        local.md5 != null && local.md5 == base.md5 -> Change.UNCHANGED

        // Unknown hash: assume modified. Safe: worst case is a keep-both conflict, never a lost edit.
        else -> Change.MODIFIED
    }

internal fun remoteChange(
    remote: RemoteEntry?,
    base: BaseEntry?,
): Change =
    when {
        base == null -> if (remote == null) Change.ABSENT else Change.NEW
        remote == null -> Change.DELETED
        remote.md5 != null && base.md5 != null -> if (remote.md5 == base.md5) Change.UNCHANGED else Change.MODIFIED
        remote.fileId == base.remoteFileId && remote.version == base.remoteVersion -> Change.UNCHANGED
        else -> Change.MODIFIED
    }

/** One file path with its three states. */
private data class FileCase(
    val path: RelPath,
    val local: LocalEntry?,
    val remote: RemoteEntry?,
    val base: BaseEntry?,
) {
    val ls = localChange(local, base)
    val rs = remoteChange(remote, base)
    val l: LocalEntry get() = checkNotNull(local) { "no local entry at $path" }
    val r: RemoteEntry get() = checkNotNull(remote) { "no remote entry at $path" }

    /** True only when both hashes are known and equal. An unchanged side inherits the base hash. */
    val sameContent: Boolean
        get() {
            val localMd5 = local?.md5 ?: base?.md5?.takeIf { ls == Change.UNCHANGED }
            val remoteMd5 = remote?.md5 ?: base?.md5?.takeIf { rs == Change.UNCHANGED }
            return localMd5 != null && localMd5 == remoteMd5
        }

    /** Both sides unchanged, but the base holds outdated metadata (touched file, new Drive version/id). */
    val baseIsStale: Boolean
        get() {
            val b = base ?: return false
            return l.size != b.localSize ||
                l.lastModified != b.localLastModified ||
                r.fileId != b.remoteFileId ||
                r.version != b.remoteVersion
        }
}

/** Counts surviving descendants per folder so a folder delete never takes live files with it. */
private class SurvivorIndex(
    initial: Collection<RelPath>,
) {
    private val present = HashSet<RelPath>()
    private val descendants = HashMap<RelPath, Int>()

    init {
        initial.forEach(::add)
    }

    fun add(path: RelPath) {
        if (present.add(path)) path.ancestors.forEach { descendants.merge(it, 1, Int::plus) }
    }

    fun remove(path: RelPath) {
        if (present.remove(path)) path.ancestors.forEach { descendants.merge(it, -1, Int::plus) }
    }

    /** Something we never delete lives at [path] (even if a same-path entry goes): keep all its ancestors. */
    fun pin(path: RelPath) {
        path.ancestors.forEach { descendants.merge(it, 1, Int::plus) }
    }

    fun hasDescendants(path: RelPath): Boolean = (descendants[path] ?: 0) > 0
}

@Suppress("TooManyFunctions") // One small function per decision keeps each rule readable and testable.
internal class PlanBuilder(
    local: List<LocalEntry>,
    remote: List<RemoteEntry>,
    /** Base, settings, conflict stamp and per-file conflict answers; its snapshots are ignored here. */
    input: PlanInput,
    preSkipped: List<Skipped>,
    /** Paths the planner does not sync (ignored, Google-native) but must never delete via a folder delete. */
    localUntracked: List<RelPath> = emptyList(),
    remoteUntracked: List<RelPath> = emptyList(),
) {
    private val settings: PairSettings = input.settings
    private val resolutions: Map<RelPath, ConflictPolicy> = input.resolutions
    private val direction = settings.direction
    private val uploadsAllowed = direction != SyncDirection.DOWNLOAD_ONLY
    private val downloadsAllowed = direction != SyncDirection.UPLOAD_ONLY
    private val localByPath = local.associateBy { it.path }
    private val baseByPath =
        input.base
            ?.entries
            .orEmpty()
            .associateBy { it.path }
    private val remoteByPath = HashMap<RelPath, RemoteEntry>()
    private val remoteDuplicates = HashMap<RelPath, List<RemoteEntry>>()

    /** Drive has both a file and a folder with this name: a type mismatch, never planned. */
    private val remoteMixedKinds = HashSet<RelPath>()

    private val actions = mutableListOf<SyncAction>()
    private val conflicts = mutableListOf<Conflict>()
    private val skipped = preSkipped.toMutableList()
    private val handled = HashSet<RelPath>()

    private val localAfter: SurvivorIndex
    private val remoteAfter: SurvivorIndex
    private val namer: ConflictNamer

    init {
        val groups = remote.groupBy { it.path }
        for ((path, group) in groups) {
            val primary = choosePrimary(group, baseByPath[path])
            remoteByPath[path] = primary
            val mixed = group.any { it.isFolder } && group.any { !it.isFolder }
            // A folder may win a mixed name only if base and local don't say this path is a file.
            val folderWins = baseByPath[path]?.isFolder != false && localByPath[path]?.isFolder != false
            val extras = group.filter { it !== primary && !it.isFolder }.sortedBy { it.fileId }
            when {
                mixed && !folderWins -> remoteMixedKinds += path
                extras.isNotEmpty() -> remoteDuplicates[path] = extras
            }
        }
        localAfter = SurvivorIndex(localByPath.keys)
        remoteAfter = SurvivorIndex(remoteByPath.keys)
        localUntracked.forEach(localAfter::pin)
        remoteUntracked.forEach(remoteAfter::pin)
        remoteMixedKinds.forEach(remoteAfter::pin)
        namer = ConflictNamer(input.conflictStamp, localByPath.keys + groups.keys + baseByPath.keys + remoteUntracked)
    }

    fun build(): SyncPlan {
        planDuplicates()
        if (downloadsAllowed) detectRemoteMoves()
        if (uploadsAllowed) detectLocalMoves()

        val allPaths = (localByPath.keys + remoteByPath.keys + baseByPath.keys).filterNot { it in handled }.sorted()
        val mismatched = allPaths.filter { it in remoteMixedKinds || kindsAt(it).size > 1 }.toSet()
        mismatched.forEach { conflicts += Conflict(it, ConflictKind.TYPE_MISMATCH, ConflictResolution.PENDING) }

        val folders = mutableListOf<RelPath>()
        for (path in allPaths) {
            if (path in mismatched || path.ancestors.any { it in mismatched }) continue
            if (kindsAt(path).single()) {
                folders += path
            } else {
                planFile(FileCase(path, localByPath[path], remoteByPath[path], baseByPath[path]))
            }
        }
        planFolders(folders)

        return SyncPlan(
            actions = actions.sortedWith(ACTION_ORDER),
            conflicts = conflicts.sortedBy { it.path },
            skipped = skipped.sortedBy { it.path },
        )
    }

    private fun kindsAt(path: RelPath): Set<Boolean> =
        listOfNotNull(localByPath[path]?.isFolder, remoteByPath[path]?.isFolder, baseByPath[path]?.isFolder).toSet()

    // ---- Drive duplicates (FR-S13) ----

    private fun choosePrimary(
        group: List<RemoteEntry>,
        base: BaseEntry?,
    ): RemoteEntry =
        group.singleOrNull()
            ?: group.firstOrNull { base?.remoteFileId != null && it.fileId == base.remoteFileId && !it.isFolder }
            ?: group.sortedWith(compareBy({ it.isFolder != (base?.isFolder ?: true) }, { it.fileId })).first()

    private fun planDuplicates() {
        for ((path, extras) in remoteDuplicates.toSortedMap()) {
            for (extra in extras) {
                if (downloadsAllowed) {
                    val copy = namer.conflictPath(path)
                    add(MoveRemote(copy, path, extra, MoveReason.DUPLICATE_RENAME))
                    add(Download(copy, extra.copy(path = copy), replaces = null))
                    conflicts += Conflict(path, ConflictKind.DUPLICATE_REMOTE_NAME, ConflictResolution.KEPT_BOTH, copy)
                } else {
                    // Left alone on Drive: it must keep its folder alive.
                    skipped += Skipped(path, SkipReason.DUPLICATE_REMOTE_NAME)
                    remoteAfter.pin(path)
                }
            }
        }
    }

    // ---- Move detection (PRD 3.9, FR-S5) ----

    /** A Drive file kept its id but changed path, while the local copy is untouched: move the local file. */
    private fun detectRemoteMoves() {
        val remoteById = remoteByPath.values.filter { !it.isFolder }.associateBy { it.fileId }
        baseByPath.values
            .sortedBy { it.path }
            .filter { !it.isFolder && it.path !in handled && it.path !in remoteByPath }
            .forEach { b ->
                val r = b.remoteFileId?.let(remoteById::get)
                val l = localByPath[b.path]
                if (r != null && l != null && isRemoteMove(b, l, r)) {
                    add(MoveLocal(r.path, b.path, l, MoveReason.DETECTED_MOVE, remote = r))
                    handled += b.path
                    handled += r.path
                }
            }
    }

    private fun isRemoteMove(
        b: BaseEntry,
        l: LocalEntry,
        r: RemoteEntry,
    ): Boolean {
        val target = r.path
        val targetFree = target !in handled && target !in baseByPath && target !in localByPath
        val unchanged = localChange(l, b) == Change.UNCHANGED && remoteChange(r, b) == Change.UNCHANGED
        return !l.isFolder && target != b.path && targetFree && unchanged
    }

    /** A base file vanished locally and a new local file has the same size + MD5: move the Drive file. */
    private fun detectLocalMoves() {
        val gone = baseByPath.values.filter(::vanishedLocally).groupBy { it.localSize to it.md5 }
        val fresh = localByPath.values.filter(::appearedLocally).groupBy { it.size to it.md5 }
        gone.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (key, olds) ->
            val old = olds.singleOrNull()
            val new = fresh[key]?.singleOrNull()
            if (old != null && new != null) {
                add(MoveRemote(new.path, old.path, remoteByPath.getValue(old.path), MoveReason.DETECTED_MOVE))
                handled += old.path
                handled += new.path
            }
        }
    }

    /** A synced file missing locally whose Drive copy is untouched. */
    private fun vanishedLocally(b: BaseEntry): Boolean {
        val r = remoteByPath[b.path]
        val candidate = !b.isFolder && b.md5 != null && b.path !in handled && b.path !in localByPath
        return candidate && r != null && !r.isFolder && remoteChange(r, b) == Change.UNCHANGED
    }

    /** A hashed local file that exists nowhere else yet. */
    private fun appearedLocally(l: LocalEntry): Boolean =
        !l.isFolder &&
            l.md5 != null &&
            l.path !in handled &&
            l.path !in baseByPath &&
            l.path !in remoteByPath

    // ---- Files (PRD 3.3 matrix) ----

    private fun planFile(c: FileCase) =
        when (direction) {
            SyncDirection.TWO_WAY -> twoWay(c)
            SyncDirection.UPLOAD_ONLY -> uploadOnly(c)
            SyncDirection.DOWNLOAD_ONLY -> downloadOnly(c)
        }

    @Suppress("CyclomaticComplexMethod") // A direct transcription of the PRD 3.3 decision table.
    private fun twoWay(c: FileCase) {
        val ls = c.ls
        val rs = c.rs
        when {
            ls == Change.UNCHANGED && rs == Change.UNCHANGED -> {
                refreshBase(c)
            }

            ls.isPresentChange && rs.isQuiet -> {
                add(Upload(c.path, c.l, replaces = c.remote))
            }

            ls.isQuiet && rs.isPresentChange -> {
                add(Download(c.path, c.r, replaces = c.local))
            }

            ls == Change.DELETED && rs == Change.UNCHANGED -> {
                add(TrashRemote(c.path, c.r))
            }

            ls == Change.UNCHANGED && rs == Change.DELETED -> {
                add(RecycleLocal(c.path, c.l))
            }

            ls == Change.DELETED && rs == Change.DELETED -> {
                add(ForgetBase(c.path))
            }

            ls == Change.MODIFIED && rs == Change.DELETED -> {
                keepModified(c, ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, Upload(c.path, c.l, replaces = null))
            }

            ls == Change.DELETED && rs == Change.MODIFIED -> {
                keepModified(c, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, Download(c.path, c.r, replaces = null))
            }

            else -> {
                bothChanged(c)
            } // MODIFIED/MODIFIED or NEW/NEW
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun uploadOnly(c: FileCase) {
        val ls = c.ls
        val rs = c.rs
        when {
            ls == Change.UNCHANGED && rs == Change.UNCHANGED -> {
                refreshBase(c)
            }

            ls.isPresentChange && rs.isQuiet -> {
                add(Upload(c.path, c.l, replaces = c.remote))
            }

            // Backup copy vanished from Drive: restore it.
            (ls == Change.UNCHANGED || ls == Change.MODIFIED) && rs == Change.DELETED -> {
                add(Upload(c.path, c.l, replaces = null))
            }

            // A Drive-only file in a backup pair is not ours to download.
            ls == Change.ABSENT -> {
                // Nothing to do.
            }

            ls == Change.DELETED -> {
                add(
                    if (settings.mirrorDeletions &&
                        rs == Change.UNCHANGED
                    ) {
                        TrashRemote(c.path, c.r)
                    } else {
                        ForgetBase(c.path)
                    },
                )
            }

            else -> {
                destinationChanged(c)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun downloadOnly(c: FileCase) {
        val ls = c.ls
        val rs = c.rs
        when {
            ls == Change.UNCHANGED && rs == Change.UNCHANGED -> {
                refreshBase(c)
            }

            rs.isPresentChange && ls.isQuiet -> {
                add(Download(c.path, c.r, replaces = c.local))
            }

            (rs == Change.UNCHANGED || rs == Change.MODIFIED) && ls == Change.DELETED -> {
                add(Download(c.path, c.r, replaces = null))
            }

            rs == Change.ABSENT -> {
                // Nothing to do.
            }

            rs == Change.DELETED -> {
                add(
                    if (settings.mirrorDeletions &&
                        ls == Change.UNCHANGED
                    ) {
                        RecycleLocal(c.path, c.l)
                    } else {
                        ForgetBase(c.path)
                    },
                )
            }

            else -> {
                destinationChanged(c)
            }
        }
    }

    /** The user's answer for this file, else the pair's policy. */
    private fun policyFor(path: RelPath): ConflictPolicy = resolutions[path] ?: settings.conflictPolicy

    private fun refreshBase(c: FileCase) {
        if (c.baseIsStale) add(Link(c.path, c.l, c.r))
    }

    /** Modify/delete conflicts always keep the modified version (PRD 3.3), unless the policy is ASK. */
    private fun keepModified(
        c: FileCase,
        kind: ConflictKind,
        restore: SyncAction,
    ) {
        if (policyFor(c.path) == ConflictPolicy.ASK) {
            conflicts += Conflict(c.path, kind, ConflictResolution.PENDING)
        } else {
            add(restore)
            conflicts += Conflict(c.path, kind, ConflictResolution.KEPT_MODIFIED)
        }
    }

    private fun bothChanged(c: FileCase) {
        if (c.sameContent) {
            add(Link(c.path, c.l, c.r))
            return
        }
        val kind = if (c.base == null) ConflictKind.BOTH_NEW else ConflictKind.BOTH_MODIFIED
        when (policyFor(c.path)) {
            ConflictPolicy.KEEP_BOTH -> {
                keepBothTwoWay(c, kind)
            }

            ConflictPolicy.LOCAL_WINS -> {
                localWins(c, kind)
            }

            ConflictPolicy.REMOTE_WINS -> {
                remoteWins(c, kind)
            }

            ConflictPolicy.NEWEST_WINS -> {
                when {
                    c.l.lastModified > c.r.modifiedTime -> localWins(c, kind)
                    c.l.lastModified < c.r.modifiedTime -> remoteWins(c, kind)
                    else -> keepBothTwoWay(c, kind)
                }
            }

            ConflictPolicy.ASK -> {
                conflicts += Conflict(c.path, kind, ConflictResolution.PENDING)
            }
        }
    }

    private fun localWins(
        c: FileCase,
        kind: ConflictKind,
    ) {
        add(Upload(c.path, c.l, replaces = c.r))
        conflicts += Conflict(c.path, kind, ConflictResolution.LOCAL_WON)
    }

    private fun remoteWins(
        c: FileCase,
        kind: ConflictKind,
    ) {
        add(Download(c.path, c.r, replaces = c.l))
        conflicts += Conflict(c.path, kind, ConflictResolution.REMOTE_WON)
    }

    /** The Drive version becomes the conflict copy; the local file keeps its name (other apps may hold it open). */
    private fun keepBothTwoWay(
        c: FileCase,
        kind: ConflictKind,
    ) {
        val copy = namer.conflictPath(c.path)
        add(MoveRemote(copy, c.path, c.r, MoveReason.CONFLICT_COPY))
        add(Upload(c.path, c.l, replaces = null))
        add(Download(copy, c.r.copy(path = copy), replaces = null))
        conflicts += Conflict(c.path, kind, ConflictResolution.KEPT_BOTH, copy)
    }

    /**
     * One-way pair whose destination diverged from the source. The destination version is kept under a
     * conflict name on the destination side only, then the source version is copied to the original path.
     */
    private fun destinationChanged(c: FileCase) {
        if (c.sameContent) {
            add(Link(c.path, c.l, c.r))
            return
        }
        val sourceModified = if (direction == SyncDirection.UPLOAD_ONLY) c.ls else c.rs
        val kind =
            when {
                c.base == null -> ConflictKind.BOTH_NEW
                sourceModified == Change.MODIFIED -> ConflictKind.BOTH_MODIFIED
                else -> ConflictKind.DESTINATION_MODIFIED
            }
        if (policyFor(c.path) == ConflictPolicy.ASK) {
            conflicts += Conflict(c.path, kind, ConflictResolution.PENDING)
            return
        }
        val copy = namer.conflictPath(c.path)
        if (direction == SyncDirection.UPLOAD_ONLY) {
            add(MoveRemote(copy, c.path, c.r, MoveReason.CONFLICT_COPY))
            add(Upload(c.path, c.l, replaces = null))
        } else {
            add(MoveLocal(copy, c.path, c.l, MoveReason.CONFLICT_COPY))
            add(Download(c.path, c.r, replaces = null))
        }
        conflicts += Conflict(c.path, kind, ConflictResolution.KEPT_BOTH, copy)
    }

    // ---- Folders (existence only, deepest first) ----

    private fun planFolders(paths: List<RelPath>) {
        for (path in paths.sortedWith(compareByDescending<RelPath> { it.depth }.thenBy { it })) {
            val l = localByPath[path]
            val r = remoteByPath[path]
            val b = baseByPath[path]
            when {
                l != null && r != null -> if (b == null) add(Link(path, l, r))
                l != null && b == null -> if (uploadsAllowed) add(CreateRemoteFolder(path))
                r != null && b == null -> if (downloadsAllowed) add(CreateLocalFolder(path))
                l != null -> remoteFolderGone(path, l)
                r != null -> localFolderGone(path, r)
                else -> add(ForgetBase(path))
            }
        }
    }

    private fun remoteFolderGone(
        path: RelPath,
        local: LocalEntry,
    ) {
        val propagate = downloadsAllowed && (direction == SyncDirection.TWO_WAY || settings.mirrorDeletions)
        when {
            propagate && !localAfter.hasDescendants(path) -> add(RecycleLocal(path, local))
            uploadsAllowed -> add(CreateRemoteFolder(path))
            else -> add(ForgetBase(path))
        }
    }

    private fun localFolderGone(
        path: RelPath,
        remote: RemoteEntry,
    ) {
        val propagate = uploadsAllowed && (direction == SyncDirection.TWO_WAY || settings.mirrorDeletions)
        when {
            propagate && !remoteAfter.hasDescendants(path) -> add(TrashRemote(path, remote))
            downloadsAllowed -> add(CreateLocalFolder(path))
            else -> add(ForgetBase(path))
        }
    }

    // ---- Bookkeeping ----

    private fun add(action: SyncAction) {
        actions += action
        when (action) {
            is CreateLocalFolder -> {
                localAfter.add(action.path)
            }

            is CreateRemoteFolder -> {
                remoteAfter.add(action.path)
            }

            is Upload -> {
                remoteAfter.add(action.path)
            }

            is Download -> {
                localAfter.add(action.path)
            }

            is TrashRemote -> {
                remoteAfter.remove(action.path)
            }

            is RecycleLocal -> {
                localAfter.remove(action.path)
            }

            is MoveLocal -> {
                localAfter.remove(action.from)
                localAfter.add(action.path)
            }

            is MoveRemote -> {
                // A renamed duplicate leaves the primary file at `from`.
                if (action.reason != MoveReason.DUPLICATE_RENAME) remoteAfter.remove(action.from)
                remoteAfter.add(action.path)
            }

            is Link, is ForgetBase -> {
                // Nothing to do.
            }
        }
    }

    private enum class Phase { CREATE_FOLDERS, MOVES, UPLOADS, DOWNLOADS, BOOKKEEPING, DELETES }

    private companion object {
        fun phase(action: SyncAction): Phase =
            when (action) {
                is CreateLocalFolder, is CreateRemoteFolder -> Phase.CREATE_FOLDERS
                is MoveLocal, is MoveRemote -> Phase.MOVES
                is Upload -> Phase.UPLOADS
                is Download -> Phase.DOWNLOADS
                is Link, is ForgetBase -> Phase.BOOKKEEPING
                is TrashRemote, is RecycleLocal -> Phase.DELETES
            }

        /** Creates parents first; deletes children first; stable by path otherwise. */
        val ACTION_ORDER: Comparator<SyncAction> =
            compareBy<SyncAction> { phase(it) }
                .thenBy { if (phase(it) == Phase.DELETES) -it.path.depth else 0 }
                .thenBy { it.path }
    }
}
