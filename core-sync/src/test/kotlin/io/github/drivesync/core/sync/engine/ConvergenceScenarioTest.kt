package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rule 2 (echo suppression / convergence), rule 7 (no base = merge), multi-device, checkpointing. */
class ConvergenceScenarioTest {
    @Test
    fun `first sync merges both sides without deleting anything`() =
        runTest {
            val p = Phone()
            p.local.write("only-local.txt", "L")
            p.local.write("same.txt", "same")
            p.local.write("diff.txt", "local version")
            p.local.write("Photos/2026/a.jpg", "jpg-a")
            p.remote.write("only-drive.txt", "D")
            val sameId = p.remote.write("same.txt", "same")
            p.remote.write("diff.txt", "drive version")
            p.remote.write("Docs/b.pdf", "pdf-b")

            val outcome = p.runClean()

            assertEquals(emptyList<RemoteOp>(), p.transfersOf("same.txt"), "same content links without transfer")
            assertEquals(sameId, p.remote.fileId("same.txt"))
            assertEquals(
                mapOf(
                    "only-local.txt" to "L",
                    "same.txt" to "same",
                    "diff.txt" to "local version",
                    copyOf("diff.txt") to "drive version",
                    "Photos/2026/a.jpg" to "jpg-a",
                    "only-drive.txt" to "D",
                    "Docs/b.pdf" to "pdf-b",
                ),
                p.local.files(),
            )
            val conflict = outcome.plan.conflicts.single()
            assertEquals(ConflictKind.BOTH_NEW, conflict.kind)
            assertEquals(ConflictResolution.KEPT_BOTH, conflict.resolution)
            p.assertMirrored()
            assertEquals(emptyList<String>(), p.remote.trashed())
            assertEquals(emptyMap<String, String>(), p.local.recycled())
            p.assertConverged()
        }

    @Test
    fun `reinstall with both sides populated links everything without transfers or deletes`() =
        runTest {
            val p = Phone()
            p.local.write("a.txt", "A")
            p.local.write("Music/song.mp3", "la la")
            p.remote.write("b.txt", "B")
            p.runClean()
            val before = p.local.files()

            p.base.clear()
            val outcome = p.runClean()

            assertTrue(outcome.plan.actions.all { it is SyncAction.Link }, outcome.plan.actions.toString())
            assertEquals(emptyList<String>(), p.writes())
            assertEquals(before, p.local.files())
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `reinstall restores files missing on one side instead of deleting them`() =
        runTest {
            val p = Phone()
            p.local.write("a.txt", "A")
            p.local.write("b.txt", "B")
            p.local.write("c.txt", "C")
            p.runClean()

            p.base.clear()
            p.local.delete("a.txt")
            p.remote.trashAt("b.txt")
            p.runClean()

            assertEquals(mapOf("a.txt" to "A", "b.txt" to "B", "c.txt" to "C"), p.local.files())
            assertEquals(emptyMap<String, String>(), p.local.recycled())
            assertEquals(listOf("b.txt"), p.remote.trashed(), "only the user's own trash")
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `every kind of change converges and the next run is empty`() =
        runTest {
            val p = Phone()
            listOf("edit-local", "edit-drive", "del-local", "del-drive", "touch", "keep").forEach {
                p.local.write("f/$it.txt", "v1 $it")
            }
            p.runClean()
            p.assertConverged()

            p.local.write("f/edit-local.txt", "v2 local")
            p.remote.write("f/edit-drive.txt", "v2 drive")
            p.local.delete("f/del-local.txt")
            p.remote.trashAt("f/del-drive.txt")
            p.local.touch("f/touch.txt")
            p.local.write("new/local.txt", "new local")
            p.remote.write("new2/drive.txt", "new drive")
            p.runClean()

            assertEquals(
                mapOf(
                    "f/edit-local.txt" to "v2 local",
                    "f/edit-drive.txt" to "v2 drive",
                    "f/touch.txt" to "v1 touch",
                    "f/keep.txt" to "v1 keep",
                    "new/local.txt" to "new local",
                    "new2/drive.txt" to "new drive",
                ),
                p.local.files(),
            )
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `two phones sharing one Drive folder converge`() =
        runTest {
            val drive = FakeRemoteStore()
            val a = Phone(drive)
            val b = Phone(drive, localStart = 5_000_000L)

            a.local.write("notes/todo.md", "1")
            a.local.write("shared.txt", "0")
            a.runClean()
            b.runClean()
            assertEquals(a.local.files(), b.local.files())

            // A → Drive → B
            a.local.write("notes/todo.md", "2")
            a.runClean()
            b.runClean()
            assertEquals("2", b.local.content("notes/todo.md"))

            // B deletes → Drive trash → A's recycle folder
            b.local.delete("notes/todo.md")
            b.runClean()
            a.runClean()
            assertFalse(a.local.exists("notes/todo.md"))
            assertEquals("2", a.local.recycled()["notes/todo.md"])
            assertTrue("notes/todo.md" in drive.trashed())

            // Simultaneous edits become keep-both on every phone.
            a.local.write("shared.txt", "from A")
            b.local.write("shared.txt", "from B")
            a.runClean()
            b.runClean()
            a.runClean()
            val expected = mapOf("shared.txt" to "from B", copyOf("shared.txt") to "from A")
            assertEquals(expected, a.local.files())
            assertEquals(expected, b.local.files())
            a.assertMirrored()
            b.assertMirrored()
            a.assertConverged()
            b.assertConverged()
        }

    @Test
    fun `base is checkpointed after every completed action, not only at the end`() =
        runTest {
            val p = Phone()
            p.local.write("a.txt", "A")
            p.local.write("b.txt", "B")
            p.local.write("c.txt", "C")
            val commitsAtUpload = mutableListOf<Int>()
            p.onRemote = { op, _ -> if (op == RemoteOp.UPLOAD) commitsAtUpload += p.base.commits }

            p.runClean()

            assertEquals(listOf(0, 1, 2), commitsAtUpload)
            assertEquals(4, p.base.commits, "three actions plus the end-of-run marker")
            assertEquals(setOf("a.txt", "b.txt", "c.txt"), p.base.paths())
        }
}
