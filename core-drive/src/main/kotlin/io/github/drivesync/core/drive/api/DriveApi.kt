package io.github.drivesync.core.drive.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag
import retrofit2.http.Url

/** Drive REST API v3, the subset the app uses. Errors are mapped by [DriveClient], so every call returns [Response]. */
@Suppress("TooManyFunctions")
interface DriveApi {
    @GET("drive/v3/files")
    suspend fun list(
        @Query("q") query: String,
        @Query("fields") fields: String = DriveFields.LIST,
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = MAX_PAGE_SIZE,
        @Query("spaces") spaces: String = "drive",
    ): Response<DriveFileList>

    @GET("drive/v3/files/{id}")
    suspend fun get(
        @Path("id") fileId: String,
        @Query("fields") fields: String = DriveFields.FILE,
    ): Response<DriveFile>

    @POST("drive/v3/files")
    suspend fun create(
        @Body metadata: DriveFileMetadata,
        @Query("fields") fields: String = DriveFields.FILE,
    ): Response<DriveFile>

    @PATCH("drive/v3/files/{id}")
    suspend fun update(
        @Path("id") fileId: String,
        @Body metadata: DriveFileMetadata,
        @Query("addParents") addParents: String? = null,
        @Query("removeParents") removeParents: String? = null,
        @Query("fields") fields: String = DriveFields.FILE,
    ): Response<DriveFile>

    @Streaming
    @GET("drive/v3/files/{id}?alt=media")
    suspend fun download(
        @Path("id") fileId: String,
    ): Response<ResponseBody>

    /** Starts a resumable upload of a new file; the session URI is in the `Location` header. */
    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun startCreateUpload(
        @Body metadata: DriveFileMetadata,
        @Query("fields") fields: String = DriveFields.FILE,
    ): Response<Unit>

    /** Starts a resumable upload that replaces the content of [fileId] (keeps its id, bumps its version). */
    @PATCH("upload/drive/v3/files/{id}?uploadType=resumable")
    suspend fun startUpdateUpload(
        @Path("id") fileId: String,
        @Body metadata: DriveFileMetadata,
        @Query("fields") fields: String = DriveFields.FILE,
    ): Response<Unit>

    /** Sends one chunk (or a status query when [body] is empty) to a resumable session. Never auto-retried. */
    @PUT
    suspend fun putChunk(
        @Url sessionUri: String,
        @Header("Content-Range") contentRange: String,
        @Body body: RequestBody,
        @Tag noRetry: NoRetry = NoRetry,
    ): Response<ResponseBody>

    @DELETE
    suspend fun cancelUpload(
        @Url sessionUri: String,
        @Tag noRetry: NoRetry = NoRetry,
    ): Response<Unit>

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val MAX_PAGE_SIZE = 1000
    }
}

/** Request tag: the caller handles failures itself, so the retry interceptor must pass the request through. */
object NoRetry
