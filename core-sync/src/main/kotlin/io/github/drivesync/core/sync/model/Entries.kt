package io.github.drivesync.core.sync.model

/** One file or folder as found by the local scanner right now. */
data class LocalEntry(
    val path: RelPath,
    val isFolder: Boolean,
    val size: Long = 0,
    /** Local last-modified time (epoch millis). Only ever compared with the local base value (PRD 3.9). */
    val lastModified: Long = 0,
    /** Lazily computed MD5 (hex). Null when not hashed; see [io.github.drivesync.core.sync.plan.HashRequirements]. */
    val md5: String? = null,
    val documentId: String? = null,
)

/** One file or folder as found on Drive right now. */
data class RemoteEntry(
    val path: RelPath,
    val fileId: String,
    val isFolder: Boolean,
    val size: Long = 0,
    /** Drive `md5Checksum`; null for Google-native files and folders. */
    val md5: String? = null,
    val version: Long = 0,
    /** Drive modifiedTime (epoch millis). Only used by the NEWEST_WINS conflict policy. */
    val modifiedTime: Long = 0,
    val mimeType: String = if (isFolder) FOLDER_MIME_TYPE else DEFAULT_MIME_TYPE,
) {
    /** Docs, Sheets, Slides, shortcuts, ...: no byte content, skipped in v1 (FR-S12, FR-S13). */
    val isGoogleNative: Boolean get() = !isFolder && mimeType.startsWith(GOOGLE_APPS_MIME_PREFIX)

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps."
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}

/**
 * The state of one path at the last successful sync ("base"). Each side is compared only with its own
 * values here, never with the other side's timestamps (PRD 3.9).
 */
data class BaseEntry(
    val path: RelPath,
    val isFolder: Boolean,
    /** Content hash both sides had at last sync; null for folders. */
    val md5: String? = null,
    val localSize: Long = 0,
    val localLastModified: Long = 0,
    val remoteFileId: String? = null,
    val remoteVersion: Long = 0,
    /**
     * Set when the Drive file has another name than [path]: Drive holds several files with that name and this
     * one is kept on the phone under a conflict name. Drive is never renamed for this; the phone copy gets the
     * Drive name back once the clash is gone. Null means the same path on both sides.
     */
    val remotePath: RelPath? = null,
)

/** Result of scanning one side. [Unavailable] must never be read as "everything was deleted" (PRD 3.9 rule 1). */
sealed interface Snapshot<out T> {
    data class Available<T>(
        val entries: List<T>,
    ) : Snapshot<T>

    data class Unavailable(
        val reason: String,
    ) : Snapshot<Nothing>
}

/** All base entries of a pair. A pair with no base at all (first sync, reinstall) is represented by `null`. */
data class BaseSnapshot(
    val entries: List<BaseEntry>,
)

/**
 * One version Drive keeps of a file (Rewind). Drive keeps versions for 30 days or 100 versions per file unless one
 * is pinned; the current content is always among them.
 */
data class RemoteVersion(
    val revisionId: String,
    val md5: String?,
    val size: Long,
    val modifiedTime: Long,
)
