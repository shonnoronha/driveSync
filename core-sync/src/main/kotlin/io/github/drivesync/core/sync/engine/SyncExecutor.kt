package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.plan.SyncPlan
import io.github.drivesync.core.sync.store.BaseStore
import io.github.drivesync.core.sync.store.BaseUpdate
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a [SyncPlan] action by action. Each action:
 * - re-checks the state the planner saw and returns [ActionOutcome.Replan] if it changed (PRD 3.9 rule 4);
 * - commits its base update right after the transfer, before the next action (rule 2, checkpointing);
 * - fails on its own without stopping unrelated actions (FR-S9). Later actions that touch a failed path
 *   (or a folder above/below it) are skipped, so a half-done keep-both never overwrites anything.
 * Uploads abort if the local file changes while reading (rule 5); downloads go temp → verify → rename (rule 6);
 * deletes are trash/recycle only (rule 10).
 */
class SyncExecutor(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val baseStore: BaseStore,
) {
    private val temps = TempFiles(local)
    private val restorer = Restorer(local, remote, temps) { commit(it) }

    suspend fun execute(
        plan: SyncPlan,
        base: BaseSnapshot?,
        progress: SyncProgressListener = SyncProgressListener.NONE,
    ): ExecutionReport {
        val baseByPath = base?.entries.orEmpty().associateBy { it.path }
        val blocked = mutableListOf<RelPath>()
        val total = plan.actions.count(SyncProgress::counts)
        var done = 0
        progress.onProgress(SyncProgress.Transferring(done, total))
        val results =
            plan.actions.map { action ->
                val touched = action.touchedPaths()
                val blocker = blocked.firstOrNull { b -> touched.any { it.overlaps(b) } }
                val outcome =
                    if (blocker != null) {
                        ActionOutcome.Skipped(blocker)
                    } else {
                        runCatchingAction { perform(action, baseByPath) }
                    }
                if (outcome != ActionOutcome.Done) blocked += touched
                if (SyncProgress.counts(action)) progress.onProgress(SyncProgress.Transferring(++done, total))
                ActionResult(action, outcome)
            }
        return ExecutionReport(results)
    }

    private suspend fun runCatchingAction(block: suspend () -> ActionOutcome): ActionOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // FR-S9: isolate any per-file failure.
        ) {
            ActionOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
        }

    private suspend fun perform(
        action: SyncAction,
        base: Map<RelPath, BaseEntry>,
    ): ActionOutcome =
        when (action) {
            is SyncAction.CreateRemoteFolder -> createRemoteFolder(action)
            is SyncAction.CreateLocalFolder -> createLocalFolder(action)
            is SyncAction.MoveRemote -> moveRemote(action, base)
            is SyncAction.MoveLocal -> moveLocal(action, base)
            is SyncAction.Upload -> upload(action)
            is SyncAction.Download -> download(action, base)
            is SyncAction.Link -> link(action, base)
            is SyncAction.ForgetBase -> commit(BaseUpdate.Remove(action.path))
            is SyncAction.TrashRemote -> trashRemote(action)
            is SyncAction.RecycleLocal -> recycleLocal(action)
            is SyncAction.Restore -> restorer.restore(action)
        }

    // ---- Folders ----

    private suspend fun createRemoteFolder(action: SyncAction.CreateRemoteFolder): ActionOutcome {
        val existing = remote.lookup(action.path)
        if (existing.any { !it.isFolder }) return ActionOutcome.Replan("a Drive file appeared at ${action.path}")
        // Idempotent (FR-S10): reuse a folder created by an interrupted earlier run.
        val folder = existing.firstOrNull() ?: remote.createFolder(action.path)
        return commit(BaseUpdate.Put(folderBase(action.path, folder.fileId)))
    }

    private suspend fun createLocalFolder(action: SyncAction.CreateLocalFolder): ActionOutcome {
        val existing = local.stat(action.path)
        if (existing != null &&
            !existing.isFolder
        ) {
            return ActionOutcome.Replan("a local file appeared at ${action.path}")
        }
        local.createFolder(action.path)
        val remoteFolder = remote.lookup(action.path).firstOrNull { it.isFolder }
        return commit(BaseUpdate.Put(folderBase(action.path, remoteFolder?.fileId)))
    }

    // ---- Moves ----

    private suspend fun moveRemote(
        action: SyncAction.MoveRemote,
        base: Map<RelPath, BaseEntry>,
    ): ActionOutcome {
        val current = remote.stat(action.remote.fileId)
        if (current == null || !current.matches(action.remote)) return ActionOutcome.Replan("Drive file changed")
        if (remote.lookup(action.path).isNotEmpty()) return ActionOutcome.Replan("${action.path} is taken on Drive")
        val moved = remote.move(action.remote.fileId, action.path)
        if (action.reason != MoveReason.DETECTED_MOVE) return ActionOutcome.Done
        // The user moved the file locally; the base entry follows it.
        val old = base[action.from] ?: return commit(BaseUpdate.Remove(action.from))
        val localNow = local.stat(action.path) ?: return commit(BaseUpdate.Remove(action.from))
        return commit(
            BaseUpdate.Remove(action.from),
            BaseUpdate.Put(
                old.copy(
                    path = action.path,
                    localSize = localNow.size,
                    localLastModified = localNow.lastModified,
                    remoteFileId = moved.fileId,
                    remoteVersion = moved.version,
                    remotePath = drivePathIfDifferent(moved, action.path),
                ),
            ),
        )
    }

    private suspend fun moveLocal(
        action: SyncAction.MoveLocal,
        base: Map<RelPath, BaseEntry>,
    ): ActionOutcome {
        val current = local.stat(action.from)
        if (current == null || !current.matches(action.local)) return ActionOutcome.Replan("local file changed")
        if (local.stat(action.path) != null) return ActionOutcome.Replan("${action.path} is taken locally")
        val moved = local.move(action.from, action.path)
        if (action.reason == MoveReason.CONFLICT_COPY) {
            // Keep-both: the original name now waits for the Drive version. Without its base entry, a failed or
            // interrupted download is retried as a new Drive file next run, not read as a phone-side delete.
            return if (action.from in base) commit(BaseUpdate.Remove(action.from)) else ActionOutcome.Done
        }
        // A Drive move or a resolved name clash: the base entry follows the file.
        val old = base[action.from] ?: return commit(BaseUpdate.Remove(action.from))
        val drive = action.remote
        return commit(
            BaseUpdate.Remove(action.from),
            BaseUpdate.Put(
                old.copy(
                    path = action.path,
                    localSize = moved.size,
                    localLastModified = moved.lastModified,
                    remoteFileId = drive?.fileId ?: old.remoteFileId,
                    remoteVersion = drive?.version ?: old.remoteVersion,
                    remotePath = if (drive != null) drivePathIfDifferent(drive, action.path) else old.remotePath,
                ),
            ),
        )
    }

    // ---- Transfers ----

    private suspend fun upload(action: SyncAction.Upload): ActionOutcome {
        val planned = action.local
        val now = local.stat(action.path)
        if (now == null || !now.matches(planned)) return ActionOutcome.Replan("local file changed since scan")
        recheckUploadTarget(action)?.let { return it }

        // Overwrites keep the Drive file's own name, which differs from the phone's for a name-clash copy.
        val drivePath = action.replaces?.path ?: action.path
        val uploaded =
            local.read(action.path) { input ->
                remote.upload(drivePath, action.replaces?.fileId, planned.lastModified, input) {
                    local.stat(action.path)?.matches(planned) == true
                }
            } ?: return ActionOutcome.Replan("local file changed during upload")

        if (planned.md5 != null && uploaded.md5 != null && planned.md5 != uploaded.md5) {
            return ActionOutcome.Failed("uploaded content does not match the local file")
        }
        return commit(
            BaseUpdate.Put(
                BaseEntry(
                    path = action.path,
                    isFolder = false,
                    md5 = uploaded.md5 ?: planned.md5,
                    localSize = planned.size,
                    localLastModified = planned.lastModified,
                    remoteFileId = uploaded.fileId,
                    remoteVersion = uploaded.version,
                    remotePath = drivePathIfDifferent(uploaded, action.path),
                ),
            ),
        )
    }

    private suspend fun recheckUploadTarget(action: SyncAction.Upload): ActionOutcome? {
        val replaces = action.replaces
        return if (replaces == null) {
            val occupied = remote.lookup(action.path).any { !it.isGoogleNative }
            if (occupied) ActionOutcome.Replan("a Drive file appeared at ${action.path}") else null
        } else {
            val current = remote.stat(replaces.fileId)
            if (current == null ||
                !current.matches(replaces)
            ) {
                ActionOutcome.Replan("Drive file changed since scan")
            } else {
                null
            }
        }
    }

    private suspend fun download(
        action: SyncAction.Download,
        base: Map<RelPath, BaseEntry>,
    ): ActionOutcome {
        val planned = action.remote
        val current = remote.stat(planned.fileId)
        if (current == null || !current.matches(planned)) return ActionOutcome.Replan("Drive file changed since scan")

        val temp = IgnoreRules.tempPathFor(action.path)
        val written = temps.write(temp) { sink -> remote.download(planned.fileId, sink) }
        val verified = temps.verify(temp, written, current.size, current.md5)
        if (verified != null) {
            local.deleteTemp(temp)
            return verified
        }

        val localNow = local.stat(action.path)
        val expected = action.replaces
        val localUnchanged = if (expected == null) localNow == null else localNow?.matches(expected) == true
        if (!localUnchanged) {
            local.deleteTemp(temp)
            return ActionOutcome.Replan("local file changed since scan")
        }

        // Keep a local version that was never synced (e.g. REMOTE_WINS overwrote local edits).
        val recycleExisting = expected != null && !expected.matchesBase(base[action.path])
        val committed = local.commitTemp(temp, action.path, recycleExisting)
        return commit(
            BaseUpdate.Put(
                BaseEntry(
                    path = action.path,
                    isFolder = false,
                    md5 = current.md5,
                    localSize = committed.size,
                    localLastModified = committed.lastModified,
                    remoteFileId = current.fileId,
                    remoteVersion = current.version,
                    remotePath = drivePathIfDifferent(current, action.path),
                ),
            ),
        )
    }

    // ---- Bookkeeping ----

    private suspend fun link(
        action: SyncAction.Link,
        base: Map<RelPath, BaseEntry>,
    ): ActionOutcome {
        val l = action.local
        val r = action.remote
        val entry =
            if (l.isFolder) {
                folderBase(action.path, r.fileId)
            } else {
                BaseEntry(
                    path = action.path,
                    isFolder = false,
                    md5 = r.md5 ?: l.md5 ?: base[action.path]?.md5,
                    localSize = l.size,
                    localLastModified = l.lastModified,
                    remoteFileId = r.fileId,
                    remoteVersion = r.version,
                    remotePath = drivePathIfDifferent(r, action.path),
                )
            }
        return commit(BaseUpdate.Put(entry))
    }

    // ---- Deletes (trash / recycle only) ----

    private suspend fun trashRemote(action: SyncAction.TrashRemote): ActionOutcome {
        val current = remote.stat(action.remote.fileId) ?: return commit(BaseUpdate.Remove(action.path))
        if (!current.matches(action.remote)) return ActionOutcome.Replan("Drive file changed since scan")
        remote.trash(current.fileId)
        return commit(BaseUpdate.Remove(action.path))
    }

    private suspend fun recycleLocal(action: SyncAction.RecycleLocal): ActionOutcome {
        val current = local.stat(action.path) ?: return commit(BaseUpdate.Remove(action.path))
        if (!current.matches(action.local)) return ActionOutcome.Replan("local file changed since scan")
        local.recycle(action.path)
        return commit(BaseUpdate.Remove(action.path))
    }

    private suspend fun commit(vararg updates: BaseUpdate): ActionOutcome {
        baseStore.commit(updates.toList())
        return ActionOutcome.Done
    }

    private companion object {
        fun folderBase(
            path: RelPath,
            remoteFileId: String?,
        ) = BaseEntry(path = path, isFolder = true, remoteFileId = remoteFileId)

        fun LocalEntry.matchesBase(base: BaseEntry?): Boolean =
            base != null && size == base.localSize && lastModified == base.localLastModified

        fun RelPath.overlaps(other: RelPath): Boolean = this == other || isAncestorOf(other) || other.isAncestorOf(this)

        fun SyncAction.touchedPaths(): List<RelPath> =
            when (this) {
                is SyncAction.MoveRemote -> listOf(path, from)
                is SyncAction.MoveLocal -> listOf(path, from)
                else -> listOf(path)
            }
    }
}

data class ExecutionReport(
    val results: List<ActionResult>,
) {
    val allDone: Boolean get() = results.all { it.outcome == ActionOutcome.Done }
}

data class ActionResult(
    val action: SyncAction,
    val outcome: ActionOutcome,
)

sealed interface ActionOutcome {
    data object Done : ActionOutcome

    /** The world changed since the scan; the next run re-plans this path (usually into a conflict). */
    data class Replan(
        val reason: String,
    ) : ActionOutcome

    data class Failed(
        val message: String,
    ) : ActionOutcome

    /** Not attempted because an earlier action on an overlapping path did not complete. */
    data class Skipped(
        val blockedBy: RelPath,
    ) : ActionOutcome
}
