package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.sync.fakes.md5Hex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** Rewind reads Drive's version history: `revisions.list` and `revisions.get?alt=media`. */
class VersionsTest {
    private val drive = FakeDriveServer()
    private val pair = drive.folder("Notes")
    private val store = DriveRemoteStore(drive.client(), pair)

    @AfterEach
    fun tearDown() = drive.close()

    @Test
    fun `lists every version`() =
        runTest {
            val id = drive.file("a.md", pair, "v1")
            drive.edit(id, "v2")
            drive.edit(id, "v3")

            val versions = store.versions(id)

            assertEquals(listOf("v1", "v2", "v3").map { md5Hex(it.toByteArray()) }, versions.map { it.md5 })
            assertEquals(listOf(2L, 2L, 2L), versions.map { it.size })
            assertEquals(versions.sortedBy { it.modifiedTime }, versions)
        }

    @Test
    fun `downloads an old version`() =
        runTest {
            val id = drive.file("a.md", pair, "first")
            drive.edit(id, "second")
            val first = store.versions(id).first()

            val out = ByteArrayOutputStream()
            store.downloadVersion(id, first.revisionId, out)

            assertEquals("first", out.toString(Charsets.UTF_8))
        }

    @Test
    fun `trashed files keep versions`() =
        runTest {
            val id = drive.file("a.md", pair, "kept")
            drive.trash(id)
            assertEquals(1, store.versions(id).size)
        }

    @Test
    fun `a file gone for good has none`() =
        runTest {
            val id = drive.file("a.md", pair, "x")
            drive.delete(id)
            assertEquals(emptyList<Any>(), store.versions(id))
        }
}
