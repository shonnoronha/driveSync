package io.github.drivesync.core.drive.api

import io.github.drivesync.core.drive.auth.AuthRequiredException
import io.github.drivesync.core.drive.http.BackoffPolicy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.IOException
import java.io.InputStream

/**
 * Drive resumable upload protocol, chunk by chunk.
 *
 * The final chunk is held back until the whole stream has been read; then `verifyBeforeCommit` runs, and only if it
 * passes is the final chunk sent, which is what creates the file revision. So a file that changed during the upload
 * never reaches Drive (PRD 3.9 rule 5). Chunk failures are recovered by asking the session how far it got.
 */
internal class ResumableUploader(
    private val api: DriveApi,
    private val chunkSize: Int,
    private val backoff: BackoffPolicy,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    suspend fun upload(
        session: String,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): DriveFile? {
        var offset = 0L
        var current = content.readChunk()
        while (true) {
            val next = if (current.size == chunkSize) content.readChunk() else EMPTY
            if (next.isEmpty()) {
                if (!verifyBeforeCommit()) {
                    cancel(session)
                    return null
                }
                val done = send(session, offset, current, total = offset + current.size)
                return (done as? Sent.Finished)?.file ?: throw DriveException.Protocol("upload did not finish")
            }
            send(session, offset, current, total = null)
            offset += current.size
            current = next
        }
    }

    /** Sends [bytes] starting at [offset], resending whatever the server did not keep, until all are acknowledged. */
    private suspend fun send(
        session: String,
        offset: Long,
        bytes: ByteArray,
        total: Long?,
    ): Sent {
        var acknowledged = 0
        var failures = 0
        while (true) {
            val result =
                try {
                    putRange(session, offset, bytes, acknowledged, total)
                } catch (e: IOException) {
                    // Transport failure: the chunk may or may not have arrived, so ask the session.
                    failures = failOrWait(failures, e.rethrowUnlessTransport())
                    queryStatus(session, total)
                }
            when (result) {
                is Sent.Finished -> {
                    return result
                }

                is Sent.Partial -> {
                    val received = (result.nextByte - offset).toInt()
                    if (received < 0) protocolError("upload session lost earlier chunks")
                    if (received >= bytes.size && total == null) return result
                    if (received <= acknowledged) failures = failOrWait(failures, null)
                    acknowledged = received.coerceAtMost(bytes.size)
                }

                Sent.Retry -> {
                    failures = failOrWait(failures, null)
                    val status = queryStatus(session, total)
                    if (status is Sent.Finished) return status
                    if (status is Sent.Partial) {
                        acknowledged =
                            (status.nextByte - offset).toInt().coerceIn(0, bytes.size)
                    }
                }
            }
        }
    }

    private suspend fun putRange(
        session: String,
        offset: Long,
        bytes: ByteArray,
        from: Int,
        total: Long?,
    ): Sent {
        val remaining = bytes.size - from
        val size = total?.toString() ?: "*"
        val range = if (remaining == 0) "bytes */$size" else "bytes ${offset + from}-${offset + bytes.size - 1}/$size"
        return interpret(api.putChunk(session, range, bytes.toRequestBody(null, from, remaining)))
    }

    private suspend fun queryStatus(
        session: String,
        total: Long?,
    ): Sent = interpret(api.putChunk(session, "bytes */${total ?: "*"}", EMPTY.toRequestBody()))

    private fun interpret(response: Response<okhttp3.ResponseBody>): Sent =
        when {
            response.isSuccessful -> {
                val body = response.body()?.use { it.string() }
                if (body.isNullOrBlank()) throw DriveException.Protocol("upload finished without a file")
                Sent.Finished(json.decodeFromString(body))
            }

            response.code() == HTTP_RESUME_INCOMPLETE -> {
                response.errorBody()?.close()
                // "Range: bytes=0-N" means bytes 0..N are stored; no header means nothing is stored yet.
                val last = response.headers()["Range"]?.substringAfterLast('-')?.toLongOrNull()
                Sent.Partial(nextByte = last?.plus(1) ?: 0)
            }

            response.code() >= HTTP_SERVER_ERROR -> {
                response.errorBody()?.close()
                Sent.Retry
            }

            else -> {
                // 404/410: the session expired. The executor fails this action; the next run starts over.
                throw response.toException()
            }
        }

    private suspend fun failOrWait(
        failures: Int,
        cause: IOException?,
    ): Int {
        if (failures >= backoff.maxRetries) throw cause ?: DriveException.Protocol("upload made no progress")
        delay(backoff.delayMillis(failures))
        return failures + 1
    }

    private fun protocolError(message: String): Nothing = throw DriveException.Protocol(message)

    /** Drive answered (or auth failed): that is final. Only transport failures are worth asking the session. */
    private fun IOException.rethrowUnlessTransport(): IOException =
        if (this is DriveException || this is AuthRequiredException) throw this else this

    private suspend fun cancel(session: String) {
        try {
            api.cancelUpload(session)
        } catch (_: IOException) {
            // Unfinished sessions expire on their own after a week and never create a file.
        }
    }

    /** Reads up to [chunkSize] bytes; fewer only at the end of the stream. (`readNBytes` needs API 33.) */
    private fun InputStream.readChunk(): ByteArray {
        val buffer = ByteArray(chunkSize)
        var filled = 0
        while (filled < chunkSize) {
            val read = read(buffer, filled, chunkSize - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == chunkSize) buffer else buffer.copyOf(filled)
    }

    sealed interface Sent {
        data class Finished(
            val file: DriveFile,
        ) : Sent

        data class Partial(
            val nextByte: Long,
        ) : Sent

        data object Retry : Sent
    }

    private companion object {
        const val HTTP_RESUME_INCOMPLETE = 308
        const val HTTP_SERVER_ERROR = 500
        val EMPTY = ByteArray(0)
        val json = Json { ignoreUnknownKeys = true }
    }
}
