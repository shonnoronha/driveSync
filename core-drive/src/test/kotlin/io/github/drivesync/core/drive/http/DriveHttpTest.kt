package io.github.drivesync.core.drive.http

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.drive.StaticAuth
import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.api.DriveException
import io.github.drivesync.core.drive.auth.AuthRequiredException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

/** Retry, auth refresh and error mapping through the production OkHttp stack (PRD 6.3). */
class DriveHttpTest {
    private val drive = FakeDriveServer()
    private val folder = drive.folder("f")
    private val sleeps = mutableListOf<Long>()

    @AfterEach
    fun tearDown() = drive.close()

    private fun client(
        auth: StaticAuth = StaticAuth(),
        maxRetries: Int = 3,
    ): DriveClient {
        val retry = RetryInterceptor(BackoffPolicy(maxRetries, baseDelayMillis = 10)) { sleeps += it }
        return DriveClient(DriveHttp.api(auth, drive.baseUrl, retry))
    }

    private fun isGet(path: String = "/drive/v3/files/") =
        { r: mockwebserver3.RecordedRequest ->
            r.method == "GET" && r.url.encodedPath.startsWith(path)
        }

    private fun isCreate() =
        { r: mockwebserver3.RecordedRequest ->
            r.method == "POST" &&
                r.url.encodedPath == "/drive/v3/files"
        }

    @Nested
    inner class Retries {
        @Test
        fun `429 is retried with backoff`() =
            runTest {
                drive.failNext(FakeDriveServer.error(429, "rateLimitExceeded"), times = 2, match = isGet())
                assertNotNull(client().getOrNull(folder))
                assertEquals(2, sleeps.size)
                assertTrue(sleeps[1] >= 20, "exponential: $sleeps")
            }

        @ParameterizedTest
        @ValueSource(strings = ["rateLimitExceeded", "userRateLimitExceeded"])
        fun `403 rate limits are retried even for creates`(reason: String) =
            runTest {
                drive.failNext(FakeDriveServer.error(403, reason), match = isCreate())
                client().createFolder("new", folder)
                assertEquals(1, drive.children(folder).size)
                assertEquals(1, sleeps.size)
            }

        @Test
        fun `other 403s are not retried`() =
            runTest {
                drive.failNext(FakeDriveServer.error(403, "insufficientFilePermissions"), match = isGet())
                val e = assertThrows<DriveException.Http> { client().getOrNull(folder) }
                assertEquals(403, e.code)
                assertEquals("insufficientFilePermissions", e.reason)
                assertEquals(emptyList<Long>(), sleeps)
            }

        @Test
        fun `5xx is retried for reads`() =
            runTest {
                drive.failNext(FakeDriveServer.error(503, "backendError"), match = isGet())
                assertNotNull(client().getOrNull(folder))
            }

        @Test
        fun `5xx is not retried for creates, so a lost reply cannot make two folders`() =
            runTest {
                drive.failNext(FakeDriveServer.error(500, "backendError"), match = isCreate())
                val e = assertThrows<DriveException.Http> { client().createFolder("new", folder) }
                assertEquals(500, e.code)
                assertEquals(1, drive.requestCount("POST", "/drive/v3/files"))
            }

        @Test
        fun `network failures are retried for reads`() =
            runTest {
                val drop = MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
                drive.failNext(drop, match = isGet())
                assertNotNull(client().getOrNull(folder))
            }

        @Test
        fun `network failures on creates are not retried`() =
            runTest {
                val drop = MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
                drive.failNext(drop, match = isCreate())
                assertThrows<IOException> { client().createFolder("new", folder) }
                assertEquals(1, drive.requestCount("POST", "/drive/v3/files"))
            }

        @Test
        fun `gives up after the last retry`() =
            runTest {
                drive.failNext(FakeDriveServer.error(429, "rateLimitExceeded"), times = 3, match = isGet())
                assertThrows<DriveException.RateLimited> { client(maxRetries = 2).getOrNull(folder) }
                assertEquals(2, sleeps.size)
            }

        @Test
        fun `honours Retry-After`() =
            runTest {
                val busy =
                    MockResponse
                        .Builder()
                        .code(429)
                        .addHeader("Retry-After", "7")
                        .build()
                drive.failNext(busy, match = isGet())
                client().getOrNull(folder)
                assertEquals(listOf(7000L), sleeps)
            }
    }

    @Nested
    inner class Auth {
        @Test
        fun `sends the bearer token`() =
            runTest {
                client().getOrNull(folder)
                assertEquals("Bearer token-1", drive.requests.last().headers["Authorization"])
            }

        @Test
        fun `a 401 invalidates the token and retries once with a fresh one`() =
            runTest {
                drive.acceptedToken = "token-2"
                val auth = StaticAuth(ArrayDeque(listOf("token-1", "token-2")))
                assertNotNull(client(auth).getOrNull(folder))
                assertEquals(listOf("token-1"), auth.invalidated)
            }

        @Test
        fun `a second 401 means the user must sign in again`() =
            runTest {
                drive.acceptedToken = "never"
                assertThrows<AuthRequiredException> { client().getOrNull(folder) }
            }

        @Test
        fun `a provider that needs the user is not retried`() =
            runTest {
                val needsUser =
                    object : io.github.drivesync.core.drive.auth.AuthProvider {
                        var calls = 0

                        override suspend fun accessToken(): String {
                            calls++
                            throw AuthRequiredException("consent needed")
                        }

                        override suspend fun invalidate(token: String) = Unit
                    }
                val retry = RetryInterceptor(BackoffPolicy(3, 10)) { sleeps += it }
                val client = DriveClient(DriveHttp.api(needsUser, drive.baseUrl, retry))
                assertThrows<AuthRequiredException> { client.getOrNull(folder) }
                assertEquals(1, needsUser.calls)
            }
    }

    @Nested
    inner class Errors {
        @Test
        fun `storage quota errors are typed so uploads can stop`() =
            runTest {
                drive.failNext(FakeDriveServer.error(403, "storageQuotaExceeded"))
                assertThrows<DriveException.QuotaExceeded> { client().createFolder("x", folder) }
            }

        @Test
        fun `404 on get means missing`() =
            runTest {
                assertEquals(null, client().getOrNull("nope"))
            }

        @Test
        fun `404 elsewhere is NotFound`() =
            runTest {
                assertThrows<DriveException.NotFound> { client().trash("nope") }
            }

        @Test
        fun `unreadable error bodies still map to Http errors`() =
            runTest {
                drive.failNext(
                    MockResponse
                        .Builder()
                        .code(400)
                        .body("<html>nope</html>")
                        .build(),
                )
                val e = assertThrows<DriveException.Http> { client().trash(folder) }
                assertEquals(400, e.code)
                assertEquals(null, e.reason)
            }

        @Test
        fun `a missing upload session URI is a protocol error`() =
            runTest {
                drive.failNext(
                    MockResponse.Builder().code(200).build(),
                    match = { it.url.encodedPath.startsWith("/upload/drive") },
                )
                assertThrows<DriveException.Protocol> {
                    client().uploadNew(folder, "a", 0, "x".byteInputStream()) { true }
                }
            }

        @Test
        fun `a failed upload start is mapped`() =
            runTest {
                drive.failNext(FakeDriveServer.error(403, "storageQuotaExceeded"))
                assertThrows<DriveException.QuotaExceeded> {
                    client().uploadReplace(folder, 0, "x".byteInputStream()) { true }
                }
            }

        @Test
        fun `an empty success body is a protocol error`() =
            runTest {
                drive.failNext(MockResponse.Builder().code(204).build(), match = isGet())
                assertThrows<DriveException.Protocol> { client().getOrNull(folder) }
            }
    }
}
