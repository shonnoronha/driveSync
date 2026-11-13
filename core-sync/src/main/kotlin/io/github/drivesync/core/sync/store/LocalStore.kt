package io.github.drivesync.core.sync.store

import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.Snapshot
import java.io.InputStream
import java.io.OutputStream

/** The phone side of ONE pair (a SAF tree in production). */
interface LocalStore {
    /** Full listing, or [Snapshot.Unavailable] when the tree can't be read (permission lost, SD card removed). */
    suspend fun scan(): Snapshot<LocalEntry>

    suspend fun stat(path: RelPath): LocalEntry?

    suspend fun md5(path: RelPath): String

    suspend fun <T> read(
        path: RelPath,
        block: suspend (InputStream) -> T,
    ): T

    /** Streams into a hidden temp file (see IgnoreRules.tempPathFor) and returns its state. */
    suspend fun writeTemp(
        temp: RelPath,
        block: suspend (OutputStream) -> Unit,
    ): LocalEntry

    /**
     * Renames a verified temp file to [target]. If [target] exists it is moved to the recycle folder first
     * when [recycleExisting] is true, otherwise replaced.
     */
    suspend fun commitTemp(
        temp: RelPath,
        target: RelPath,
        recycleExisting: Boolean,
    ): LocalEntry

    suspend fun deleteTemp(temp: RelPath)

    /** Idempotent: returns the existing folder if it is already there. */
    suspend fun createFolder(path: RelPath): LocalEntry

    suspend fun move(
        from: RelPath,
        to: RelPath,
    ): LocalEntry

    /** Moves a file or folder into the pair's recycle folder. Never a permanent delete (PRD 3.9 rule 10). */
    suspend fun recycle(path: RelPath)
}
