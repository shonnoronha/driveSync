package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.store.LocalStore
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** The Drive name to remember for a phone file kept under another name (a Drive name clash). */
internal fun drivePathIfDifferent(
    drive: RemoteEntry,
    phonePath: RelPath,
): RelPath? = drive.path.takeIf { it != phonePath }

/** Rule 4: the Drive file is still the one the planner saw. */
internal fun RemoteEntry.matches(planned: RemoteEntry): Boolean {
    val sameContent = if (md5 != null && planned.md5 != null) md5 == planned.md5 else version == planned.version
    return fileId == planned.fileId && path == planned.path && isFolder == planned.isFolder && sameContent
}

/** Rule 4 on the phone: folders only need to still be folders; files must keep size and mtime. */
internal fun LocalEntry.matches(planned: LocalEntry): Boolean =
    isFolder == planned.isFolder && (isFolder || (size == planned.size && lastModified == planned.lastModified))

/** Hidden temp files for downloads and restores (PRD 3.9 rule 6). */
internal class TempFiles(
    private val local: LocalStore,
) {
    /** Writes [temp]; on failure the half-written file is removed (a killed run leaves it for the next run). */
    suspend fun write(
        temp: RelPath,
        block: suspend (OutputStream) -> Unit,
    ): LocalEntry =
        try {
            local.writeTemp(temp, block)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            runCatching { local.deleteTemp(temp) }
            throw e
        }

    /** The temp file must have the expected size and MD5 before it may replace anything. */
    suspend fun verify(
        temp: RelPath,
        written: LocalEntry,
        expectedSize: Long,
        expectedMd5: String?,
    ): ActionOutcome? {
        if (written.size != expectedSize) {
            return ActionOutcome.Failed("downloaded ${written.size} bytes, expected $expectedSize")
        }
        val md5 = expectedMd5 ?: return null
        return if (local.md5(temp) == md5) null else ActionOutcome.Failed("downloaded file failed MD5 check")
    }
}
