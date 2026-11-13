package io.github.drivesync.core.drive.http

import io.github.drivesync.core.drive.api.NoRetry
import io.github.drivesync.core.drive.auth.AuthRequiredException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Retries transient Drive failures with exponential backoff and jitter (PRD 6.3):
 * - `429` and `403` with `rateLimitExceeded`/`userRateLimitExceeded`: always, because Drive rejected the request.
 * - `5xx` and network errors: only for idempotent methods, so a lost reply can never create a second folder.
 * Requests tagged [NoRetry] (resumable upload chunks) pass straight through; the uploader recovers those itself.
 */
class RetryInterceptor(
    private val policy: BackoffPolicy = BackoffPolicy(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(NoRetry::class.java) != null) return chain.proceed(request)
        var attempt = 0
        while (true) {
            val response =
                try {
                    chain.proceed(request)
                } catch (e: AuthRequiredException) {
                    throw e
                } catch (e: IOException) {
                    if (!request.isIdempotent || attempt >= policy.maxRetries || chain.call().isCanceled()) throw e
                    null
                }
            if (response != null && (attempt >= policy.maxRetries || !shouldRetry(request, response))) return response
            val retryAfter = response?.retryAfterMillis()
            response?.close()
            sleep(policy.delayMillis(attempt, retryAfter))
            if (chain.call().isCanceled()) throw InterruptedIOException("Canceled during backoff")
            attempt++
        }
    }

    private fun shouldRetry(
        request: Request,
        response: Response,
    ): Boolean =
        when {
            response.code == HTTP_TOO_MANY_REQUESTS -> true
            response.code == HTTP_FORBIDDEN -> DriveErrors.reason(response.peekBody(PEEK_BYTES).string()) in RATE_LIMITS
            response.code >= HTTP_SERVER_ERROR -> request.isIdempotent
            else -> false
        }

    private fun Response.retryAfterMillis(): Long? = header("Retry-After")?.toLongOrNull()?.times(MILLIS_PER_SECOND)

    private val Request.isIdempotent: Boolean get() = method in IDEMPOTENT_METHODS

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val PEEK_BYTES = 64L * 1024
        const val MILLIS_PER_SECOND = 1000L
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE", "PATCH")
        val RATE_LIMITS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}
