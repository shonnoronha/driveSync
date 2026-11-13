package io.github.drivesync.core.drive.http

import kotlin.random.Random

/** Exponential backoff with jitter (PRD 6.3). Attempt 0 is the first retry. */
class BackoffPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val random: Random = Random.Default,
) {
    /** Delay before retry number [attempt]; a server `Retry-After` wins when it is longer. */
    fun delayMillis(
        attempt: Int,
        retryAfterMillis: Long? = null,
    ): Long {
        val exponential = (baseDelayMillis shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMillis)
        val jitter = if (baseDelayMillis > 0) random.nextLong(baseDelayMillis) else 0
        return maxOf(exponential + jitter, retryAfterMillis ?: 0)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_BASE_DELAY_MILLIS = 1_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 32_000L
        private const val MAX_SHIFT = 20
    }
}
