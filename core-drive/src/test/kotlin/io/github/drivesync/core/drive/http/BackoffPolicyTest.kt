package io.github.drivesync.core.drive.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BackoffPolicyTest {
    private val policy =
        BackoffPolicy(maxRetries = 5, baseDelayMillis = 100, maxDelayMillis = 1_000, random = Random(42))

    @Test
    fun `doubles each attempt plus up to one base delay of jitter`() {
        listOf(100L, 200L, 400L, 800L).forEachIndexed { attempt, expected ->
            val delay = policy.delayMillis(attempt)
            assertTrue(delay in expected until expected + 100, "attempt $attempt: $delay")
        }
    }

    @Test
    fun `is capped`() {
        assertTrue(policy.delayMillis(60) in 1_000L until 1_100L)
    }

    @Test
    fun `a longer Retry-After wins`() {
        assertEquals(5_000L, policy.delayMillis(0, retryAfterMillis = 5_000))
        assertTrue(policy.delayMillis(0, retryAfterMillis = 1) < 200)
    }

    @Test
    fun `zero base delay means no waiting`() {
        assertEquals(0L, BackoffPolicy(baseDelayMillis = 0).delayMillis(3))
    }
}
