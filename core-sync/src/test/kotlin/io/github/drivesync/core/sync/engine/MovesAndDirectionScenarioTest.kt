package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Move detection (PRD 3.9, FR-S5) and one-way pairs (rule 8, ADR 0002 decisions 3 and 4). */
class MovesAndDirectionScenarioTest {
    private suspend fun syncedPhone(vararg files: Pair<String, String>): Phone {
        val p = Phone()
        files.forEach { (path, content) -> p.local.write(path, content) }
        p.runClean()
        return p
    }

    private fun Phone.noTransfers() {
        val transfers = remoteOps.filter { it.first == RemoteOp.UPLOAD || it.first == RemoteOp.DOWNLOAD }
        assertEquals(emptyList<Any>(), transfers)
        assertEquals(emptyList<Any>(), localOps.filter { it.first == LocalOp.WRITE_TEMP })
    }

    @Nested
    inner class Moves {
        @Test
        fun `local rename moves the Drive file without uploading`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                val id = p.remote.fileId("a.txt")
                p.local.rename("a.txt", "renamed.txt")

                p.runClean()

                p.noTransfers()
                assertEquals(id, p.remote.fileId("renamed.txt"))
                assertEquals(emptyList<String>(), p.remote.trashed())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local move into a new subfolder moves the Drive file`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                val id = p.remote.fileId("a.txt")
                p.local.rename("a.txt", "Archive/2026/a.txt")

                p.runClean()

                p.noTransfers()
                assertEquals(id, p.remote.fileId("Archive/2026/a.txt"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `Drive rename moves the local file without downloading`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                p.remote.rename("a.txt", "renamed.txt")

                p.runClean()

                p.noTransfers()
                assertEquals("A", p.local.content("renamed.txt"))
                assertFalse(p.local.exists("a.txt"))
                assertEquals(emptyMap<String, String>(), p.local.recycled())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local folder rename converges without re-uploading`() =
            runTest {
                val p = syncedPhone("Trip/a.jpg" to "aaa", "Trip/b.jpg" to "bbbb", "keep.txt" to "k")
                val ids = listOf("Trip/a.jpg", "Trip/b.jpg").map(p.remote::fileId)
                p.local.rename("Trip", "Trip 2026")

                p.runClean()

                p.noTransfers()
                assertEquals(ids, listOf("Trip 2026/a.jpg", "Trip 2026/b.jpg").map(p.remote::fileId))
                assertEquals(listOf("Trip"), p.remote.trashed(), "only the empty old folder")
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `Drive folder rename converges without downloading`() =
            runTest {
                val p = syncedPhone("Trip/a.jpg" to "aaa", "Trip/b.jpg" to "bbbb", "keep.txt" to "k")
                p.remote.rename("Trip", "Trip 2026")

                p.runClean()

                p.noTransfers()
                val expected = mapOf("Trip 2026/a.jpg" to "aaa", "Trip 2026/b.jpg" to "bbbb", "keep.txt" to "k")
                assertEquals(expected, p.local.files())
                assertEquals(emptyMap<String, String>(), p.local.recycled())
                p.assertMirrored()
                p.assertConverged()
            }
    }

    @Nested
    inner class UploadOnly {
        private val backup = PairSettings(direction = SyncDirection.UPLOAD_ONLY)
        private val mirrored = backup.copy(mirrorDeletions = true)

        private suspend fun Phone.runBackup(settings: PairSettings = backup) {
            runClean(settings)
            assertEquals(emptyList<Any>(), localOps.filter { it.first in LOCAL_WRITES }, "never writes locally")
            assertConverged(settings)
        }

        @Test
        fun `upload-only backs up, restores and never touches the phone`() =
            runTest {
                val p = Phone()
                listOf("a", "b", "c", "d").forEach { p.local.write("$it.txt", it.uppercase()) }
                p.remote.write("extra.txt", "E")
                p.remote.write("Extra/x.txt", "X")
                val localBefore = p.local.files()

                p.runBackup()
                assertEquals(localBefore, p.local.files())
                assertEquals("E", p.remote.content("extra.txt"))
                assertFalse(p.local.exists("Extra"))

                // Destination edit: kept on Drive under a conflict name, the phone version wins the path.
                p.remote.write("a.txt", "edited on the web")
                p.runBackup()
                assertEquals("A", p.remote.content("a.txt"))
                assertEquals("edited on the web", p.remote.content(copyOf("a.txt")))
                assertFalse(p.local.exists(copyOf("a.txt")))

                // Deleted from Drive: restored.
                p.remote.trashAt("b.txt")
                p.runBackup()
                assertEquals("B", p.remote.content("b.txt"))

                // Local delete without mirror: Drive keeps the backup.
                p.local.delete("c.txt")
                p.runBackup()
                assertEquals("C", p.remote.content("c.txt"))

                // Local delete with mirror: Drive trash.
                p.local.delete("d.txt")
                p.runBackup(mirrored)
                assertFalse("d.txt" in p.remote.files())
                assertTrue("d.txt" in p.remote.trashed())
                assertEquals(emptyMap<String, String>(), p.local.recycled())
                p.assertNoDuplicates()
            }
    }

    @Nested
    inner class DownloadOnly {
        private val mirror = PairSettings(direction = SyncDirection.DOWNLOAD_ONLY)
        private val mirrored = mirror.copy(mirrorDeletions = true)

        private suspend fun Phone.runMirror(settings: PairSettings = mirror) {
            runClean(settings)
            assertEquals(emptyList<Any>(), remoteOps.filter { it.first in REMOTE_WRITES }, "never writes to Drive")
            assertConverged(settings)
        }

        @Test
        fun `download-only mirrors Drive, restores and never touches Drive`() =
            runTest {
                val p = Phone()
                listOf("a", "b", "c", "d").forEach { p.remote.write("$it.txt", it.uppercase()) }
                p.local.write("extra.txt", "E")
                p.local.write("Extra/x.txt", "X")
                val driveBefore = p.remote.files()

                p.runMirror()
                assertEquals(driveBefore, p.remote.files())
                val expected = setOf("a.txt", "b.txt", "c.txt", "d.txt", "extra.txt", "Extra/x.txt")
                assertEquals(expected, p.local.files().keys)
                assertFalse("Extra" in p.remote.folders())

                // Destination edit: kept locally under a conflict name, the Drive version wins the path.
                p.local.write("a.txt", "edited on the phone")
                p.runMirror()
                assertEquals("A", p.local.content("a.txt"))
                assertEquals("edited on the phone", p.local.content(copyOf("a.txt")))

                // Deleted locally: restored.
                p.local.delete("b.txt")
                p.runMirror()
                assertEquals("B", p.local.content("b.txt"))

                // Drive delete without mirror: the phone keeps its copy.
                p.remote.trashAt("c.txt")
                p.runMirror()
                assertEquals("C", p.local.content("c.txt"))

                // Drive delete with mirror: local recycle folder.
                p.remote.trashAt("d.txt")
                p.runMirror(mirrored)
                assertFalse(p.local.exists("d.txt"))
                assertEquals(mapOf("d.txt" to "D"), p.local.recycled())
                assertEquals(driveBefore - setOf("c.txt", "d.txt"), p.remote.files())
            }
    }
}
