package io.github.drivesync.core.drive.http

import io.github.drivesync.core.drive.api.DriveErrorBody
import io.github.drivesync.core.drive.api.DriveException
import io.github.drivesync.core.drive.auth.AuthRequiredException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Maps Drive error responses to typed exceptions (PRD 6.3). */
object DriveErrors {
    private val json = Json { ignoreUnknownKeys = true }

    fun reason(body: String?): String? = parse(body)?.errors?.firstOrNull()?.reason

    fun toException(
        code: Int,
        body: String?,
    ): Exception {
        val details = parse(body)
        val reason = details?.errors?.firstOrNull()?.reason
        val message = details?.message?.takeIf { it.isNotBlank() } ?: "Drive request failed"
        return when {
            code == HTTP_UNAUTHORIZED -> AuthRequiredException("Drive rejected the access token: $message")
            code == HTTP_NOT_FOUND -> DriveException.NotFound(message)
            reason == "storageQuotaExceeded" -> DriveException.QuotaExceeded(message)
            reason in RATE_LIMITS || code == HTTP_TOO_MANY_REQUESTS -> DriveException.RateLimited(message)
            else -> DriveException.Http(code, reason, message)
        }
    }

    private fun parse(body: String?): DriveErrorBody.Details? =
        body?.takeIf { it.isNotBlank() }?.let {
            try {
                json.decodeFromString<DriveErrorBody>(it).error
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val RATE_LIMITS = setOf("rateLimitExceeded", "userRateLimitExceeded")
}
