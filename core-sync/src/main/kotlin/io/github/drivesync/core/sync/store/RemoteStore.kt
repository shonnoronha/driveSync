package io.github.drivesync.core.sync.store

import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import java.io.InputStream
import java.io.OutputStream

/**
 * The Drive side of ONE pair, rooted at the pair's Drive folder. The sync engine talks to Drive only through
 * this interface (PRD 3.8), so Advanced mode or another provider needs a new implementation, not a new engine.
 * Implementations throw on transport errors; the executor isolates failures per action (FR-S9).
 */
interface RemoteStore {
    val accessMode: AccessMode

    /** Full listing of the pair's tree, or [Snapshot.Unavailable] if the pair folder is missing/trashed. */
    suspend fun scan(): Snapshot<RemoteEntry>

    /** Current state of one file, or null if it no longer exists or is trashed. */
    suspend fun stat(fileId: String): RemoteEntry?

    /** Non-trashed entries currently at [path] (Drive allows several). */
    suspend fun lookup(path: RelPath): List<RemoteEntry>

    /** Creates a folder at [path]; the parent must exist. */
    suspend fun createFolder(path: RelPath): RemoteEntry

    /**
     * Uploads [content] to [path], overwriting [replacesFileId] or creating a new file when it is null.
     * [verifyBeforeCommit] runs after all bytes are read and before the upload is finalized; returning false
     * abandons the upload and this returns null (PRD 3.9 rule 5).
     */
    suspend fun upload(
        path: RelPath,
        replacesFileId: String?,
        modifiedTime: Long,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): RemoteEntry?

    suspend fun download(
        fileId: String,
        sink: OutputStream,
    )

    /** Renames and/or re-parents a file or folder. */
    suspend fun move(
        fileId: String,
        to: RelPath,
    ): RemoteEntry

    /** Moves to Drive trash. Never a permanent delete (PRD 3.9 rule 10). */
    suspend fun trash(fileId: String)
}
