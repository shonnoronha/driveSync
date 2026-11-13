package io.github.drivesync.core.localstorage

import io.github.drivesync.core.sync.fakes.md5Hex
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class DocumentTreeLocalStoreTest {
    @TempDir
    lateinit var dir: File

    private val tree by lazy { FileDocumentTree(dir) }
    private val store by lazy { DocumentTreeLocalStore(tree, Dispatchers.Unconfined) }

    private fun p(path: String) = RelPath.of(path)

    private val temp = p("notes/.a.md.drivesync-part")

    private suspend fun writeTemp(
        path: RelPath = temp,
        content: String = "downloaded",
    ): LocalEntry = store.writeTemp(path) { it.write(content.toByteArray()) }

    @Nested
    inner class Scan {
        @Test
        fun `lists files and folders with sizes, mtimes and document ids`() =
            runTest {
                tree.write("a.md", "alpha")
                tree.write("2026/nov/b.md", "beta")
                File(dir, "empty").mkdir()
                val entries =
                    assertInstanceOf(Snapshot.Available::class.java, store.scan())
                        .entries
                        .map { it as LocalEntry }
                        .associateBy { it.path.value }
                assertEquals(setOf("2026", "2026/nov", "2026/nov/b.md", "a.md", "empty"), entries.keys)
                with(entries.getValue("a.md")) {
                    assertEquals(5L, size)
                    assertEquals(File(dir, "a.md").lastModified(), lastModified)
                    assertEquals("a.md", documentId)
                    assertNull(md5)
                }
                assertTrue(entries.getValue("2026/nov").isFolder)
                assertEquals(0L, entries.getValue("2026").lastModified)
            }

        @Test
        fun `an empty folder is available and empty`() =
            runTest {
                assertEquals(Snapshot.Available(emptyList<LocalEntry>()), store.scan())
            }

        @Test
        fun `a revoked grant is unavailable, never empty`() =
            runTest {
                tree.write("a.md", "x")
                tree.unavailable = true
                assertInstanceOf(Snapshot.Unavailable::class.java, store.scan())
            }

        @Test
        fun `a deleted root folder is unavailable`() =
            runTest {
                val gone = File(dir, "pair").also { it.mkdir() }
                val pairStore = DocumentTreeLocalStore(FileDocumentTree(gone), Dispatchers.Unconfined)
                gone.delete()
                assertInstanceOf(Snapshot.Unavailable::class.java, pairStore.scan())
            }

        @Test
        fun `a root that is a file is unavailable`() =
            runTest {
                val file = File(dir, "f").also { it.writeText("x") }
                val fileStore = DocumentTreeLocalStore(FileDocumentTree(file), Dispatchers.Unconfined)
                assertInstanceOf(Snapshot.Unavailable::class.java, fileStore.scan())
            }
    }

    @Nested
    inner class Reads {
        @Test
        fun `stat finds files and folders, and null for missing`() =
            runTest {
                tree.write("d/a.md", "alpha")
                assertEquals(5L, store.stat(p("d/a.md"))!!.size)
                assertTrue(store.stat(p("d"))!!.isFolder)
                assertNull(store.stat(p("d/missing")))
                assertNull(store.stat(p("d/a.md/below")))
            }

        @Test
        fun `stat sees renames made after the scan`() =
            runTest {
                tree.write("old/a.md", "x")
                store.scan()
                tree.userMove("old", "new")
                assertNull(store.stat(p("old/a.md")))
                assertNotNull(store.stat(p("new/a.md")))
            }

        @Test
        fun `md5 and read stream the content`() =
            runTest {
                val big = "x".repeat(200_000)
                tree.write("big.bin", big)
                assertEquals(md5Hex(big), store.md5(p("big.bin")))
                assertEquals(big, store.read(p("big.bin")) { it.readBytes().decodeToString() })
            }

        @Test
        fun `reading a folder or missing file fails`() =
            runTest {
                File(dir, "d").mkdir()
                assertThrows<IOException> { store.md5(p("d")) }
                assertThrows<IOException> { store.read(p("missing")) { it.read() } }
            }
    }

    @Nested
    inner class Downloads {
        @Test
        fun `writeTemp streams into a hidden file next to the target`() =
            runTest {
                File(dir, "notes").mkdir()
                val entry = writeTemp()
                assertEquals(10L, entry.size)
                assertEquals("downloaded", tree.read("notes/.a.md.drivesync-part"))
            }

        @Test
        fun `a leftover temp from a killed run is replaced`() =
            runTest {
                tree.write("notes/.a.md.drivesync-part", "half a file from before")
                writeTemp(content = "new")
                assertEquals("new", tree.read("notes/.a.md.drivesync-part"))
            }

        @Test
        fun `writeTemp needs parent and temp name`() =
            runTest {
                assertThrows<IOException> { writeTemp() }
                assertThrows<IllegalArgumentException> { writeTemp(p("a.md")) }
            }

        @Test
        fun `failed stream leaves temp for cleanup`() =
            runTest {
                File(dir, "notes").mkdir()
                assertThrows<IOException> {
                    store.writeTemp(temp) {
                        it.write("part".toByteArray())
                        throw IOException("network lost")
                    }
                }
                assertEquals("part", tree.read("notes/.a.md.drivesync-part"))
                store.deleteTemp(temp)
                assertFalse(tree.exists("notes/.a.md.drivesync-part"))
            }

        @Test
        fun `commit renames the temp to the target`() =
            runTest {
                File(dir, "notes").mkdir()
                writeTemp()
                val entry = store.commitTemp(temp, p("notes/a.md"), recycleExisting = false)
                assertEquals(p("notes/a.md"), entry.path)
                assertEquals(mapOf("notes/a.md" to "downloaded"), tree.files())
            }

        @Test
        fun `commit recycles a target that holds unsynced content`() =
            runTest {
                tree.write("notes/a.md", "local edit")
                writeTemp()
                store.commitTemp(temp, p("notes/a.md"), recycleExisting = true)
                assertEquals(
                    mapOf(".drivesync-recycle/notes/a.md" to "local edit", "notes/a.md" to "downloaded"),
                    tree.files(),
                )
            }

        @Test
        fun `commit replaces a target that is already synced`() =
            runTest {
                tree.write("notes/a.md", "old synced copy")
                writeTemp()
                store.commitTemp(temp, p("notes/a.md"), recycleExisting = false)
                assertEquals(mapOf("notes/a.md" to "downloaded"), tree.files())
            }

        @Test
        fun `commit reports a name the phone changed`() =
            runTest {
                tree.caseInsensitive = true
                tree.write("notes/A.md", "other file")
                writeTemp()
                assertThrows<IOException> { store.commitTemp(temp, p("notes/a.md"), recycleExisting = false) }
                assertEquals("other file", tree.read("notes/A.md"))
            }

        @Test
        fun `illegal temp names fail before writing`() =
            runTest {
                File(dir, "notes").mkdir()
                assertThrows<IOException> { writeTemp(p("notes/.a:b.drivesync-part")) }
                assertEquals(emptyMap<String, String>(), tree.files())
            }

        @Test
        fun `commit needs temp next to target`() =
            runTest {
                File(dir, "notes").mkdir()
                writeTemp()
                assertThrows<IllegalStateException> { store.commitTemp(temp, p("a.md"), recycleExisting = false) }
            }

        @Test
        fun `deleteTemp refuses real files`() =
            runTest {
                store.deleteTemp(p(".x.drivesync-part"))
                tree.write("a.md", "keep")
                assertThrows<IllegalArgumentException> { store.deleteTemp(p("a.md")) }
                assertEquals("keep", tree.read("a.md"))
            }
    }

    @Nested
    inner class Folders {
        @Test
        fun `createFolder makes missing parents and is idempotent`() =
            runTest {
                val created = store.createFolder(p("a/b/c"))
                assertTrue(created.isFolder)
                assertEquals(created.documentId, store.createFolder(p("a/b/c")).documentId)
                assertTrue(File(dir, "a/b/c").isDirectory)
            }

        @Test
        fun `createFolder over a file fails`() =
            runTest {
                tree.write("a", "file")
                assertThrows<IOException> { store.createFolder(p("a")) }
                assertThrows<IOException> { store.createFolder(p("a/b")) }
            }

        @Test
        fun `createFolder rejects illegal names`() =
            runTest {
                assertThrows<IOException> { store.createFolder(p("what?")) }
                assertFalse(File(dir, "what_").exists())
            }

        @Test
        fun `a folder the phone renames is reported`() =
            runTest {
                tree.caseInsensitive = true
                File(dir, "Photos").mkdir()
                assertThrows<IOException> { store.createFolder(p("photos")) }
            }
    }

    @Nested
    inner class Moves {
        @Test
        fun `renames in place`() =
            runTest {
                tree.write("d/a.md", "x")
                val moved = store.move(p("d/a.md"), p("d/b.md"))
                assertEquals(p("d/b.md"), moved.path)
                assertEquals(mapOf("d/b.md" to "x"), tree.files())
            }

        @Test
        fun `moves to another folder, with and without a rename`() =
            runTest {
                tree.write("a/x.md", "x")
                tree.write("a/y.md", "y")
                File(dir, "b").mkdir()
                store.move(p("a/x.md"), p("b/x.md"))
                store.move(p("a/y.md"), p("b/z.md"))
                assertEquals(mapOf("b/x.md" to "x", "b/z.md" to "y"), tree.files())
            }

        @Test
        fun `moves first when the new name is taken in the old folder`() =
            runTest {
                tree.write("a/x.md", "x")
                tree.write("a/z.md", "stays")
                File(dir, "b").mkdir()
                store.move(p("a/x.md"), p("b/z.md"))
                assertEquals(mapOf("a/z.md" to "stays", "b/z.md" to "x"), tree.files())
            }

        @Test
        fun `fails when both orders would clash`() =
            runTest {
                tree.write("a/x.md", "x")
                tree.write("a/z.md", "1")
                tree.write("b/x.md", "2")
                assertThrows<IOException> { store.move(p("a/x.md"), p("b/z.md")) }
                assertEquals(3, tree.files().size)
            }

        @Test
        fun `never overwrites the target`() =
            runTest {
                tree.write("a.md", "a")
                tree.write("b.md", "b")
                assertThrows<IOException> { store.move(p("a.md"), p("b.md")) }
                assertThrows<IOException> { store.move(p("missing"), p("c.md")) }
                assertThrows<IOException> { store.move(p("a.md"), p("nofolder/a.md")) }
                assertEquals(mapOf("a.md" to "a", "b.md" to "b"), tree.files())
            }

        @Test
        fun `moves whole folders and forgets their old paths`() =
            runTest {
                tree.write("a/sub/x.md", "x")
                store.scan()
                store.move(p("a"), p("b"))
                assertNull(store.stat(p("a/sub/x.md")))
                assertEquals("x", store.read(p("b/sub/x.md")) { it.readBytes().decodeToString() })
            }

        @Test
        fun `no move support - copy then delete`() =
            runTest {
                tree.supportsMove = false
                tree.write("a/x.md", "x")
                tree.write("a/f/y.md", "y")
                File(dir, "b").mkdir()
                store.move(p("a/x.md"), p("b/x.md"))
                store.move(p("a/f"), p("b/f"))
                assertEquals(mapOf("b/f/y.md" to "y", "b/x.md" to "x"), tree.files())
            }

        @Test
        fun `rejects names the phone cannot hold`() =
            runTest {
                tree.write("a.md", "a")
                assertThrows<IOException> { store.move(p("a.md"), p("a|b.md")) }
            }
    }

    @Nested
    inner class Recycle {
        @Test
        fun `moves files and folders into the recycle folder, keeping their path`() =
            runTest {
                tree.write("d/a.md", "a")
                tree.write("f/b.md", "b")
                store.recycle(p("d/a.md"))
                store.recycle(p("f"))
                assertEquals(
                    mapOf(".drivesync-recycle/d/a.md" to "a", ".drivesync-recycle/f/b.md" to "b"),
                    tree.files(),
                )
            }

        @Test
        fun `recycling the same path twice keeps both`() =
            runTest {
                tree.write("a.md", "first")
                store.recycle(p("a.md"))
                tree.write("a.md", "second")
                store.recycle(p("a.md"))
                assertEquals(
                    mapOf(".drivesync-recycle/a.md" to "first", ".drivesync-recycle/a.md (2)" to "second"),
                    tree.files(),
                )
            }

        @Test
        fun `recycling a missing file fails`() =
            runTest {
                assertThrows<IOException> { store.recycle(p("missing")) }
            }
    }

    @Test
    fun `the phone's name rules`() {
        assertNull(LocalNames.problem("Notes 2026.md"))
        listOf("", "a:b", "a?", "a\u0001", "trailing.", "trailing ", "x".repeat(256)).forEach {
            assertNotNull(LocalNames.problem(it), it)
        }
    }
}
