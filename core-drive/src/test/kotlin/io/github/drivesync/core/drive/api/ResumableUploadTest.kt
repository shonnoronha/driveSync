package io.github.drivesync.core.drive.api

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.sync.fakes.md5Hex
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Recovery paths of the resumable upload protocol. */
class ResumableUploadTest {
    private val drive = FakeDriveServer()
    private val folder = drive.folder("f")
    private val client = drive.client(chunkSize = 8)
    private val content = "0123456789abcdefghijklmnop" // 26 bytes, 4 chunks

    @AfterEach
    fun tearDown() = drive.close()

    private val isChunk = { r: RecordedRequest ->
        r.method == "PUT" && r.headers["Content-Range"]?.contains(
            "-",
        ) == true
    }

    private suspend fun upload() = client.uploadNew(folder, "a.txt", 0, content.byteInputStream()) { true }!!

    @Test
    fun `resends what Drive did not keep`() =
        runTest {
            drive.chunkAcceptLimit = 5
            val file = upload()
            assertEquals(content, drive.node(file.id).content.decodeToString())
            assertEquals(md5Hex(content), file.md5Checksum)
        }

    @Test
    fun `a 5xx on a chunk asks the session for its state and continues`() =
        runTest {
            drive.failNext(FakeDriveServer.error(503, "backendError"), match = isChunk)
            val file = upload()
            assertEquals(content, drive.node(file.id).content.decodeToString())
            assertEquals(1, drive.requests.count { it.headers["Content-Range"] == "bytes */*" })
        }

    @Test
    fun `a dropped connection on a chunk is recovered`() =
        runTest {
            val drop = MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
            drive.failNext(drop, match = isChunk)
            assertEquals(content, drive.node(upload().id).content.decodeToString())
        }

    @Test
    fun `a 5xx on the final chunk is resent`() =
        runTest {
            drive.failNext(FakeDriveServer.error(500, "backendError"), match = {
                it.headers["Content-Range"]?.endsWith("/26") ==
                    true
            })
            assertEquals(content, drive.node(upload().id).content.decodeToString())
        }

    @Test
    fun `gives up when Drive keeps failing`() =
        runTest {
            drive.failNext(FakeDriveServer.error(503, "backendError"), times = 20, match = { it.method == "PUT" })
            assertThrows<DriveException> { upload() }
        }

    @Test
    fun `gives up when Drive keeps acknowledging nothing`() =
        runTest {
            drive.chunkAcceptLimit = 0
            assertThrows<DriveException.Protocol> { upload() }
        }

    @Test
    fun `an expired session fails the upload`() =
        runTest {
            drive.failNext(FakeDriveServer.error(404, "notFound"), match = isChunk)
            assertThrows<DriveException.NotFound> { upload() }
        }

    @Test
    fun `a session that lost earlier chunks is a protocol error`() =
        runTest {
            var chunks = 0
            drive.failNext(MockResponse.Builder().code(308).build(), match = { isChunk(it) && ++chunks == 2 })
            assertThrows<DriveException.Protocol> { upload() }
        }

    @Test
    fun `cancel failures are ignored`() =
        runTest {
            val drop = MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
            drive.failNext(drop, match = { it.method == "DELETE" })
            assertEquals(null, client.uploadNew(folder, "a", 0, content.byteInputStream()) { false })
        }

    @Test
    fun `an empty success body is a protocol error`() =
        runTest {
            drive.failNext(MockResponse.Builder().code(200).build(), match = isChunk)
            assertThrows<DriveException.Protocol> { upload() }
        }

    @Test
    fun `rejects a non-positive chunk size`() {
        val api =
            io.github.drivesync.core.drive.http.DriveHttp.api(
                io.github.drivesync.core.drive
                    .StaticAuth(),
                drive.baseUrl,
            )
        assertThrows<IllegalArgumentException> {
            ResumableUploader(
                api,
                0,
                io.github.drivesync.core.drive.http
                    .BackoffPolicy(),
            )
        }
    }
}
