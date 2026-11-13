package io.github.drivesync.core.drive.api

import kotlinx.serialization.Serializable

/** A Drive v3 `File` resource, limited to the fields this app requests. Drive sends int64 values as strings. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val size: String? = null,
    val md5Checksum: String? = null,
    val version: String? = null,
    val modifiedTime: String? = null,
    val createdTime: String? = null,
    val parents: List<String> = emptyList(),
    val trashed: Boolean = false,
    val appProperties: Map<String, String> = emptyMap(),
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}

@Serializable
data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/** Request body for create/update. Null fields are left out of the JSON, so they stay unchanged on update. */
@Serializable
data class DriveFileMetadata(
    val name: String? = null,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val modifiedTime: String? = null,
    val trashed: Boolean? = null,
    val appProperties: Map<String, String>? = null,
)

/** Drive's JSON error body: `{"error": {"code": 403, "message": "...", "errors": [{"reason": "..."}]}}`. */
@Serializable
internal data class DriveErrorBody(
    val error: Details? = null,
) {
    @Serializable
    data class Details(
        val code: Int = 0,
        val message: String = "",
        val errors: List<Reason> = emptyList(),
    )

    @Serializable
    data class Reason(
        val reason: String = "",
    )
}

/** Partial-response field lists (PRD 6.3: request only what is needed). */
object DriveFields {
    const val FILE = "id,name,mimeType,size,md5Checksum,version,modifiedTime,createdTime,parents,trashed,appProperties"
    const val LIST = "nextPageToken,files($FILE)"
}
