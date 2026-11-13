package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
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
    suspend fun execute(
        plan: SyncPlan,
        base: BaseSnapshot?,
    ): ExecutionReport {
        val baseByPath = base?.entries.orEmpty().associateBy { it.path }
        val blocked = mutableListOf<RelPath>()
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
        if (action.reason != MoveReason.DETECTED_MOVE) return ActionOutcome.Done
        val old = base[action.from] ?: return commit(BaseUpdate.Remove(action.from))
        return commit(
            BaseUpdate.Remove(action.from),
            BaseUpdate.Put(
                old.copy(
                    path = action.path,
                    localSize = moved.size,
                    localLastModified = moved.lastModified,
                    remoteFileId = action.remote?.fileId ?: old.remoteFileId,
                    remoteVersion = action.remote?.version ?: old.remoteVersion,
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

        val uploaded =
            local.read(action.path) { input ->
                remote.upload(action.path, action.replaces?.fileId, planned.lastModified, input) {
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
        val written =
            try {
                local.writeTemp(temp) { sink -> remote.download(planned.fileId, sink) }
            } catch (e: CancellationException) {
                throw e // A killed run leaves the temp file; the next run deletes it.
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                runCatching { local.deleteTemp(temp) }
                throw e
            }
        val verified = verifyTemp(temp, written, current)
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
                ),
            ),
        )
    }

    /** Rule 6: the temp file must have the expected size and MD5 before it may replace anything. */
    private suspend fun verifyTemp(
        temp: RelPath,
        written: LocalEntry,
        expected: RemoteEntry,
    ): ActionOutcome? {
        if (written.size != expected.size) {
            return ActionOutcome.Failed("downloaded ${written.size} bytes, expected ${expected.size}")
        }
        val md5 = expected.md5 ?: return null
        return if (local.md5(temp) == md5) null else ActionOutcome.Failed("downloaded file failed MD5 check")
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

        fun RemoteEntry.matches(planned: RemoteEntry): Boolean {
            val sameContent = if (md5 != null && planned.md5 != null) md5 == planned.md5 else version == planned.version
            return fileId == planned.fileId && path == planned.path && isFolder == planned.isFolder && sameContent
        }

        /** Folders only need to still be folders; files must keep size and mtime. */
        fun LocalEntry.matches(planned: LocalEntry): Boolean =
            isFolder == planned.isFolder && (isFolder || (size == planned.size && lastModified == planned.lastModified))

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
