package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.store.BaseUpdate
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore

/**
 * Runs a Rewind [SyncAction.Restore]: Drive first, then the phone. A crash in between leaves Drive newer and the
 * phone unchanged, which the next sync finishes as a plain download. If the phone file changes meanwhile, the next
 * sync sees both sides changed and keeps both, so nothing is lost either way.
 */
internal class Restorer(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val temps: TempFiles,
    private val commit: suspend (BaseUpdate) -> ActionOutcome,
) {
    suspend fun restore(action: SyncAction.Restore): ActionOutcome {
        recheck(action)?.let { return it }
        val version = action.version
        val temp = IgnoreRules.tempPathFor(action.path)
        val written =
            temps.write(temp) { sink -> remote.downloadVersion(action.sourceFileId, version.revisionId, sink) }
        temps.verify(temp, written, version.size, version.md5)?.let {
            local.deleteTemp(temp)
            return it
        }
        val uploaded =
            local.read(temp) { input ->
                remote.upload(action.path, action.replacesRemote?.fileId, written.lastModified, input) {
                    local.stat(temp)?.matches(written) == true
                }
            }
        if (uploaded == null) {
            local.deleteTemp(temp)
            return ActionOutcome.Replan("restored file changed before upload")
        }
        val sameContent = version.md5 == null || uploaded.md5 == null || uploaded.md5 == version.md5
        if (!sameContent) {
            local.deleteTemp(temp)
            return ActionOutcome.Failed("Drive did not get the restored content")
        }
        if (!localStillAsPlanned(action.path, action.replacesLocal)) {
            local.deleteTemp(temp)
            return ActionOutcome.Replan("local file changed during the rewind")
        }
        val committed = local.commitTemp(temp, action.path, recycleExisting = false)
        return commit(
            BaseUpdate.Put(
                BaseEntry(
                    path = action.path,
                    isFolder = false,
                    md5 = uploaded.md5 ?: version.md5,
                    localSize = committed.size,
                    localLastModified = committed.lastModified,
                    remoteFileId = uploaded.fileId,
                    remoteVersion = uploaded.version,
                    remotePath = drivePathIfDifferent(uploaded, action.path),
                ),
            ),
        )
    }

    /** Rule 4 for rewinds: both sides must still be as planned. */
    private suspend fun recheck(action: SyncAction.Restore): ActionOutcome? {
        val replaces = action.replacesRemote
        val driveOk =
            if (replaces == null) {
                remote.lookup(action.path).none { !it.isGoogleNative }
            } else {
                remote.stat(replaces.fileId)?.matches(replaces) == true
            }
        return when {
            !driveOk -> ActionOutcome.Replan("Drive file changed since the rewind was planned")
            !localStillAsPlanned(action.path, action.replacesLocal) -> ActionOutcome.Replan("local file changed")
            else -> null
        }
    }

    private suspend fun localStillAsPlanned(
        path: RelPath,
        expected: LocalEntry?,
    ): Boolean {
        val now = local.stat(path)
        return if (expected == null) now == null else now?.matches(expected) == true
    }
}
