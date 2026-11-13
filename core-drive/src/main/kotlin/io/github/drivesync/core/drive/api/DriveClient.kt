package io.github.drivesync.core.drive.api

import io.github.drivesync.core.drive.http.BackoffPolicy
import io.github.drivesync.core.drive.http.DriveErrors
import retrofit2.Response
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * Thin, typed layer over [DriveApi]: maps error responses to exceptions, follows pagination and runs
 * resumable uploads. Knows nothing about pairs or paths.
 */
class DriveClient(
    private val api: DriveApi,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val uploadBackoff: BackoffPolicy = BackoffPolicy(),
) {
    /** Every page of a `files.list` query. */
    suspend fun listAll(query: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val page = api.list(query, pageToken = pageToken).body("list files")
            files += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return files
    }

    /** The file, or null when Drive says it does not exist (or this app cannot see it). */
    suspend fun getOrNull(fileId: String): DriveFile? {
        val response = api.get(fileId)
        return if (response.code() == HTTP_NOT_FOUND) null else response.body("get $fileId")
    }

    suspend fun createFolder(
        name: String,
        parentId: String,
        appProperties: Map<String, String>? = null,
    ): DriveFile =
        api
            .create(
                DriveFileMetadata(name, DriveFile.FOLDER_MIME_TYPE, listOf(parentId), appProperties = appProperties),
            ).body("create folder $name")

    /** Renames and/or re-parents. Parents change only when [newParentId] differs from [oldParentIds]. */
    suspend fun move(
        fileId: String,
        newName: String,
        newParentId: String,
        oldParentIds: List<String>,
    ): DriveFile {
        val reparent = newParentId !in oldParentIds
        return api
            .update(
                fileId,
                DriveFileMetadata(name = newName),
                addParents = newParentId.takeIf { reparent },
                removeParents = oldParentIds.joinToString(",").takeIf { reparent && oldParentIds.isNotEmpty() },
            ).body("move $fileId")
    }

    /** Moves to Drive trash; never a permanent delete (PRD 3.9 rule 10). */
    suspend fun trash(fileId: String): DriveFile =
        api.update(fileId, DriveFileMetadata(trashed = true)).body("trash $fileId")

    suspend fun download(
        fileId: String,
        sink: OutputStream,
    ) {
        val body = api.download(fileId).body("download $fileId")
        body.use { it.byteStream().copyTo(sink) }
    }

    /** Uploads a new file into [parentId]. Returns null when [verifyBeforeCommit] abandoned it. */
    suspend fun uploadNew(
        parentId: String,
        name: String,
        modifiedTime: Long,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): DriveFile? {
        val metadata = DriveFileMetadata(name = name, parents = listOf(parentId), modifiedTime = rfc3339(modifiedTime))
        val session = api.startCreateUpload(metadata).sessionUri("start upload of $name")
        return uploader().upload(session, content, verifyBeforeCommit)
    }

    /** Replaces the content of [fileId], keeping its id. Returns null when [verifyBeforeCommit] abandoned it. */
    suspend fun uploadReplace(
        fileId: String,
        modifiedTime: Long,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): DriveFile? {
        val metadata = DriveFileMetadata(modifiedTime = rfc3339(modifiedTime))
        val session = api.startUpdateUpload(fileId, metadata).sessionUri("start upload to $fileId")
        return uploader().upload(session, content, verifyBeforeCommit)
    }

    private fun uploader() = ResumableUploader(api, chunkSize, uploadBackoff)

    private fun Response<Unit>.sessionUri(what: String): String {
        if (!isSuccessful) throw toException()
        return headers()["Location"] ?: throw DriveException.Protocol("$what: no upload session URI")
    }

    companion object {
        /** Resumable chunks must be multiples of 256 KiB (except the last one). */
        const val CHUNK_GRANULARITY = 256 * 1024
        const val DEFAULT_CHUNK_SIZE = 16 * CHUNK_GRANULARITY
        private const val HTTP_NOT_FOUND = 404

        fun rfc3339(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

        fun epochMillis(rfc3339: String?): Long = rfc3339?.let { Instant.parse(it).toEpochMilli() } ?: 0
    }
}

internal fun <T> Response<T>.body(what: String): T {
    if (!isSuccessful) throw toException()
    return body() ?: throw DriveException.Protocol("$what: empty response body")
}

internal fun Response<*>.toException(): Exception = DriveErrors.toException(code(), errorBody()?.use { it.string() })
