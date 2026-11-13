package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.sync.fakes.md5Hex
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.store.AccessMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException

class DriveRemoteStoreTest {
    private val drive = FakeDriveServer()
    private val pair = drive.folder("Notes")
    private val store = DriveRemoteStore(drive.client(), pair)

    @AfterEach
    fun tearDown() = drive.close()

    private fun p(path: String) = RelPath.of(path)

    private suspend fun scanned(): List<RemoteEntry> =
        assertInstanceOf(Snapshot.Available::class.java, store.scan()).entries.map { it as RemoteEntry }

    @Test
    fun `uses app-folder access`() {
        assertEquals(AccessMode.APP_FOLDER, store.accessMode)
    }

    @Nested
    inner class Scan {
        @Test
        fun `lists the whole tree with paths, sizes, hashes and versions`() =
            runTest {
                val sub = drive.folder("2026", pair)
                val a = drive.file("a.md", pair, "alpha")
                drive.file("b.md", sub, "beta")
                val entries = scanned().associateBy { it.path.value }
                assertEquals(setOf("2026", "a.md", "2026/b.md"), entries.keys)
                assertTrue(entries.getValue("2026").isFolder)
                with(entries.getValue("a.md")) {
                    assertEquals(a, fileId)
                    assertEquals(5L, size)
                    assertEquals(md5Hex("alpha"), md5)
                    assertEquals(drive.node(a).version, version)
                    assertEquals(drive.node(a).modifiedTime, modifiedTime)
                    assertFalse(isFolder)
                }
            }

        @Test
        fun `skips trashed files and everything under a trashed folder`() =
            runTest {
                drive.trash(drive.file("gone.md", pair))
                val sub = drive.folder("old", pair)
                drive.file("inner.md", sub)
                drive.trash(sub)
                drive.file("kept.md", pair)
                assertEquals(listOf("kept.md"), scanned().map { it.path.value })
            }

        @Test
        fun `follows pagination`() =
            runTest {
                drive.pageLimit = 2
                repeat(5) { drive.file("f$it", pair) }
                assertEquals(5, scanned().size)
            }

        @Test
        fun `batches sibling folders into few list queries`() =
            runTest {
                repeat(45) { drive.file("x.md", drive.folder("d$it", pair)) }
                assertEquals(90, scanned().size)
                // Depth 1: one query; depth 2: 45 folders in two batches; depth 3: no folders.
                assertEquals(
                    3,
                    drive.requestCount("GET", "/drive/v3/files") - drive.requestCount("GET", "/drive/v3/files/"),
                )
            }

        @Test
        fun `keeps same-name duplicates and Google Docs for the planner to handle`() =
            runTest {
                drive.file("a.md", pair, "one")
                drive.file("a.md", pair, "two")
                drive.file("Plan", pair, "", mimeType = "application/vnd.google-apps.document")
                val entries = scanned()
                assertEquals(2, entries.count { it.path == p("a.md") })
                assertTrue(entries.single { it.path == p("Plan") }.isGoogleNative)
            }

        @Test
        fun `maps names that are not valid path segments`() =
            runTest {
                drive.file("a/b", pair)
                drive.file("..", pair)
                assertEquals(setOf("a_b", "_.."), scanned().map { it.path.value }.toSet())
            }

        @Test
        fun `a missing pair folder is unavailable, never empty`() =
            runTest {
                drive.delete(pair)
                assertInstanceOf(Snapshot.Unavailable::class.java, store.scan())
            }

        @Test
        fun `a trashed pair folder is unavailable`() =
            runTest {
                drive.trash(pair)
                assertInstanceOf(Snapshot.Unavailable::class.java, store.scan())
            }

        @Test
        fun `a pair folder inside a trashed root is unavailable`() =
            runTest {
                val root = drive.folder("Drive Sync")
                val inner = drive.folder("Photos", root)
                drive.trash(root)
                assertInstanceOf(Snapshot.Unavailable::class.java, DriveRemoteStore(drive.client(), inner).scan())
            }

        @Test
        fun `a pair id that is a file is unavailable`() =
            runTest {
                val file = drive.file("x", pair)
                assertInstanceOf(Snapshot.Unavailable::class.java, DriveRemoteStore(drive.client(), file).scan())
            }
    }

    @Nested
    inner class Stat {
        @Test
        fun `rebuilds the current path from parents`() =
            runTest {
                val deep = drive.folder("b", drive.folder("a", pair))
                val id = drive.file("c.md", deep, "x")
                val entry = store.stat(id)!!
                assertEquals(p("a/b/c.md"), entry.path)
                assertEquals(md5Hex("x"), entry.md5)
            }

        @Test
        fun `sees renames done elsewhere`() =
            runTest {
                val id = drive.file("old.md", pair)
                drive.node(id).name = "new.md"
                assertEquals(p("new.md"), store.stat(id)!!.path)
            }

        @Test
        fun `is null for missing, trashed, moved-out and under-trashed-folder files`() =
            runTest {
                val missing = drive.file("m", pair).also { drive.delete(it) }
                val trashed = drive.file("t", pair).also { drive.trash(it) }
                val outside = drive.file("o", pair).also { drive.reparent(it, FakeDriveServer.MY_DRIVE) }
                val folder = drive.folder("f", pair)
                val underTrash = drive.file("u", folder).also { drive.node(folder).trashed = true }
                listOf(missing, trashed, outside, underTrash).forEach { assertNull(store.stat(it), it) }
            }

        @Test
        fun `is null for a file with no parents`() =
            runTest {
                val id = drive.file("orphan", pair).also { drive.node(it).parents.clear() }
                assertNull(store.stat(id))
            }
    }

    @Nested
    inner class Lookup {
        @Test
        fun `finds every live entry at a path`() =
            runTest {
                val sub = drive.folder("d", pair)
                val one = drive.file("a.md", sub, "1")
                val two = drive.file("a.md", sub, "2")
                drive.trash(drive.file("a.md", sub, "3"))
                assertEquals(setOf(one, two), store.lookup(p("d/a.md")).map { it.fileId }.toSet())
            }

        @Test
        fun `looks through duplicate parent folders`() =
            runTest {
                drive.file("x", drive.folder("d", pair))
                drive.file("x", drive.folder("d", pair))
                assertEquals(2, store.lookup(p("d/x")).size)
            }

        @Test
        fun `is empty when the parent folder is missing`() =
            runTest {
                assertEquals(emptyList<RemoteEntry>(), store.lookup(p("nope/a.md")))
            }

        @Test
        fun `is empty when the parent is a file`() =
            runTest {
                drive.file("d", pair)
                assertEquals(emptyList<RemoteEntry>(), store.lookup(p("d/a.md")))
            }

        @Test
        fun `is empty when the pair folder is gone`() =
            runTest {
                drive.delete(pair)
                assertEquals(emptyList<RemoteEntry>(), store.lookup(p("a.md")))
            }

        @Test
        fun `finds files whose Drive names were mapped`() =
            runTest {
                val id = drive.file("a/b", pair)
                drive.file("a_b", pair)
                assertEquals(2, store.lookup(p("a_b")).size)
                assertTrue(store.lookup(p("a_b")).any { it.fileId == id })
            }

        @Test
        fun `escapes quotes in names`() =
            runTest {
                val id = drive.file("it's.md", pair)
                assertEquals(id, store.lookup(p("it's.md")).single().fileId)
            }
    }

    @Nested
    inner class Writes {
        @Test
        fun `creates nested folders`() =
            runTest {
                val a = store.createFolder(p("a"))
                val b = store.createFolder(p("a/b"))
                assertTrue(b.isFolder)
                assertEquals(p("a/b"), b.path)
                assertEquals(listOf(a.fileId), drive.node(b.fileId).parents)
            }

        @Test
        fun `creating a folder under a missing parent fails`() =
            runTest {
                assertThrows<IOException> { store.createFolder(p("missing/b")) }
            }

        @Test
        fun `new folders go into the lowest-id duplicate parent`() =
            runTest {
                val first = drive.folder("d", pair)
                drive.folder("d", pair)
                assertEquals(listOf(first), drive.node(store.createFolder(p("d/x")).fileId).parents)
            }

        @Test
        fun `uploads a new multi-chunk file`() =
            runTest {
                drive.folder("d", pair)
                val content = "0123456789abcdefghij" // 20 bytes, 3 chunks of 8
                val entry = store.upload(p("d/new.txt"), null, MTIME, content.byteInputStream()) { true }!!
                assertEquals(content, drive.node(entry.fileId).content.decodeToString())
                assertEquals(p("d/new.txt"), entry.path)
                assertEquals(md5Hex(content), entry.md5)
                assertEquals(MTIME, entry.modifiedTime)
                assertEquals(3, drive.requestCount("PUT", "/upload/session/"))
            }

        @Test
        fun `uploads empty files and exact chunk multiples`() =
            runTest {
                val empty = store.upload(p("empty"), null, MTIME, ByteArray(0).inputStream()) { true }!!
                assertEquals(0L, empty.size)
                val exact = store.upload(p("exact"), null, MTIME, "12345678abcdefgh".byteInputStream()) { true }!!
                assertEquals("12345678abcdefgh", drive.node(exact.fileId).content.decodeToString())
            }

        @Test
        fun `replacing keeps the file id and bumps the version`() =
            runTest {
                val id = drive.file("a.md", pair, "old")
                val before = drive.node(id).version
                val entry = store.upload(p("a.md"), id, MTIME, "new".byteInputStream()) { true }!!
                assertEquals(id, entry.fileId)
                assertEquals("new", drive.node(id).content.decodeToString())
                assertTrue(entry.version > before)
                assertEquals(1, drive.children(pair).size)
            }

        @Test
        fun `a failed verification abandons the upload before Drive commits it`() =
            runTest {
                var verified = 0
                val result =
                    store.upload(p("a.md"), null, MTIME, "0123456789abcdef!".byteInputStream()) {
                        verified++
                        false
                    }
                assertNull(result)
                assertEquals(1, verified)
                assertEquals(emptyList<FakeDriveServer.Node>(), drive.children(pair))
                assertEquals(1, drive.requestCount("DELETE", "/upload/session/"))
            }

        @Test
        fun `an abandoned replace leaves the old content`() =
            runTest {
                val id = drive.file("a.md", pair, "old")
                assertNull(store.upload(p("a.md"), id, MTIME, "new".byteInputStream()) { false })
                assertEquals("old", drive.node(id).content.decodeToString())
            }

        @Test
        fun `uploading into a missing folder fails`() =
            runTest {
                assertThrows<IOException> { store.upload(p("nope/a"), null, MTIME, "x".byteInputStream()) { true } }
            }

        @Test
        fun `downloads content`() =
            runTest {
                val id = drive.file("a.md", pair, "hello")
                val sink = ByteArrayOutputStream()
                store.download(id, sink)
                assertEquals("hello", sink.toString())
            }

        @Test
        fun `renames in place without touching parents`() =
            runTest {
                val id = drive.file("a.md", pair)
                val moved = store.move(id, p("b.md"))
                assertEquals(p("b.md"), moved.path)
                assertEquals("b.md", drive.node(id).name)
                assertEquals(listOf(pair), drive.node(id).parents)
                val patch = drive.requests.last { it.method == "PATCH" }
                assertNull(patch.url.queryParameter("addParents"))
            }

        @Test
        fun `moves into another folder`() =
            runTest {
                val dest = drive.folder("dest", drive.folder("a", pair))
                val id = drive.file("x.md", pair)
                store.move(id, p("a/dest/y.md"))
                assertEquals(listOf(dest), drive.node(id).parents)
                assertEquals("y.md", drive.node(id).name)
                assertEquals(p("a/dest/y.md"), store.stat(id)!!.path)
            }

        @Test
        fun `moving a missing file fails`() =
            runTest {
                val id = drive.file("x", pair).also { drive.delete(it) }
                assertThrows<IOException> { store.move(id, p("y")) }
            }

        @Test
        fun `trash moves to Drive trash and keeps the file`() =
            runTest {
                val id = drive.file("a.md", pair)
                store.trash(id)
                assertTrue(drive.exists(id))
                assertTrue(drive.node(id).trashed)
            }
    }

    private companion object {
        const val MTIME = 1_750_000_000_000L
    }
}
