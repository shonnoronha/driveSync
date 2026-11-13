package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.store.BaseUpdate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

/** FR-S9 (per-file error isolation), FR-S10 / NFR-1 (crash idempotency). */
class FailureRecoveryScenarioTest {
    private suspend fun syncedPhone(vararg files: Pair<String, String>): Phone {
        val p = Phone()
        files.forEach { (path, content) -> p.local.write(path, content) }
        p.local.write("anchor.txt", "anchor")
        p.runClean()
        return p
    }

    /** Kills the process right before the base commit that records [path]. */
    private fun Phone.killBeforeCommitOf(path: String) {
        var fired = false
        base.beforeCommit = { updates ->
            if (!fired && updates.any { it is BaseUpdate.Put && it.entry.path.value == path }) {
                fired = true
                killed()
            }
        }
    }

    @Nested
    inner class ErrorIsolation {
        @Test
        fun `one failing upload does not stop the others and is retried next run`() =
            runTest {
                val p = Phone()
                p.local.write("a.txt", "A")
                p.local.write("b.txt", "B")
                p.local.write("c.txt", "C")
                p.onRemote = { op, path ->
                    if (op == RemoteOp.UPLOAD && path?.value == "b.txt") throw IOException("network down")
                }

                val first = p.run()

                assertEquals(ActionOutcome.Done, first.outcomeOf<SyncAction.Upload>("a.txt"))
                assertEquals(ActionOutcome.Failed("network down"), first.outcomeOf<SyncAction.Upload>("b.txt"))
                assertEquals(ActionOutcome.Done, first.outcomeOf<SyncAction.Upload>("c.txt"))
                assertEquals(setOf("a.txt", "c.txt"), p.remote.files().keys)
                assertNull(p.base.entry("b.txt"))

                p.restart()
                p.runClean()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `failed keep-both rename blocks the upload and download of the same paths`() =
            runTest {
                val p = syncedPhone("doc.txt" to "v1")
                p.local.write("doc.txt", "local edit")
                p.remote.write("doc.txt", "drive edit")
                p.onRemote = { op, path ->
                    if (op == RemoteOp.MOVE && path?.value == "doc.txt") throw IOException("rate limited")
                }

                val first = p.run()

                val copy = copyOf("doc.txt")
                assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.MoveRemote>(copy))
                assertInstanceOf(ActionOutcome.Skipped::class.java, first.outcomeOf<SyncAction.Upload>("doc.txt"))
                assertInstanceOf(ActionOutcome.Skipped::class.java, first.outcomeOf<SyncAction.Download>(copy))
                assertEquals(listOf("drive edit"), p.remote.files()["doc.txt"])
                assertFalse(copy in p.remote.files())
                assertEquals("local edit", p.local.content("doc.txt"))
                assertEquals(emptyList<String>(), p.writes().filter { "UPLOAD" in it || "COMMIT_TEMP" in it })

                p.restart()
                val second = p.runClean()
                assertEquals(
                    ConflictResolution.KEPT_BOTH,
                    second.plan.conflicts
                        .single()
                        .resolution,
                )
                assertEquals("local edit", p.local.content("doc.txt"))
                assertEquals("drive edit", p.local.content(copyOf("doc.txt")))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `failed base write after an upload links the file next run without uploading again`() =
            runTest {
                val p = syncedPhone()
                p.local.write("a.txt", "A")
                var fired = false
                p.base.beforeCommit = { updates ->
                    if (!fired && updates.isNotEmpty()) {
                        fired = true
                        throw IOException("disk full")
                    }
                }

                val first = p.run()
                assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Upload>("a.txt"))

                p.restart()
                val second = p.runClean()
                assertEquals(emptyList<RemoteOp>(), p.transfersOf("a.txt"))
                assertInstanceOf(SyncAction.Link::class.java, second.plan.actions.single())
                p.assertMirrored()
                p.assertConverged()
            }
    }

    @Nested
    inner class Crashes {
        @Test
        fun `killed before the third upload resumes without duplicates`() =
            runTest {
                val p = Phone()
                val names = (1..5).map { "photo$it.jpg" }
                names.forEach { p.local.write(it, "bytes of $it") }
                var uploads = 0
                p.onRemote = { op, _ -> if (op == RemoteOp.UPLOAD && ++uploads == 3) killed() }

                p.crash()

                assertEquals(setOf("photo1.jpg", "photo2.jpg"), p.remote.files().keys)
                assertEquals(setOf("photo1.jpg", "photo2.jpg"), p.base.paths())

                p.restart()
                p.runClean()
                assertEquals(names.toSet(), p.remote.files().keys)
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `killed after a Drive folder was created but before its base commit reuses the folder`() =
            runTest {
                val p = syncedPhone()
                p.local.write("Album/p1.jpg", "one")
                p.local.write("Album/p2.jpg", "two")
                p.killBeforeCommitOf("Album")

                p.crash()

                assertEquals(setOf("Album"), p.remote.folders())
                assertFalse("Album" in p.base.paths())

                p.restart()
                p.runClean()
                assertEquals(1, p.remote.lookup(RelPath.of("Album")).size, "no duplicate Drive folder")
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `first run killed before its first base commit merges on the next run`() =
            runTest {
                val p = Phone()
                p.local.write("Album/p1.jpg", "one")
                p.remote.write("drive.txt", "D")
                p.killBeforeCommitOf("Album")

                p.crash()
                assertNull(p.base.load(), "still no base")
                assertEquals(setOf("Album"), p.remote.folders())

                p.restart()
                p.runClean()
                assertEquals(1, p.remote.lookup(RelPath.of("Album")).size, "no duplicate Drive folder")
                assertEquals(emptyList<String>(), p.remote.trashed())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `killed between upload and base commit links next run`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                val id = p.remote.fileId("a.txt")
                p.local.write("a.txt", "v2")
                p.killBeforeCommitOf("a.txt")

                p.crash()
                assertEquals("v2", p.remote.content("a.txt"))

                p.restart()
                val second = p.runClean()
                assertEquals(emptyList<RemoteOp>(), p.transfersOf("a.txt"))
                assertEquals(emptyList<Any>(), second.plan.conflicts)
                assertEquals(id, p.remote.fileId("a.txt"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `killed between download commit and base commit links next run without a conflict`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.write("a.txt", "drive v2")
                p.remote.write("b.txt", "drive new")
                p.killBeforeCommitOf("a.txt")

                p.crash()
                assertEquals("drive v2", p.local.content("a.txt"))

                p.restart()
                val second = p.runClean()
                assertEquals(emptyList<RemoteOp>(), p.transfersOf("a.txt"))
                assertEquals(emptyList<Any>(), second.plan.conflicts)
                assertEquals(emptyMap<String, String>(), p.local.recycled())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `killed after the temp file was written retries the download cleanly`() =
            runTest {
                val p = syncedPhone()
                p.remote.write("new.txt", "drive")
                p.onLocal = once(LocalOp.COMMIT_TEMP, "new.txt") { killed() }

                p.crash()
                assertFalse(p.local.exists("new.txt"))

                p.restart()
                p.runClean()
                assertEquals("drive", p.local.content("new.txt"))
                p.assertNoTempFiles()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `killed in the middle of a keep-both keeps both versions on the next run`() =
            runTest {
                val p = syncedPhone("doc.txt" to "v1")
                p.local.write("doc.txt", "local edit")
                p.remote.write("doc.txt", "drive edit")
                p.onRemote = once(RemoteOp.UPLOAD, "doc.txt") { killed() }

                p.crash()
                assertEquals(listOf("drive edit"), p.remote.files()[copyOf("doc.txt")])

                p.restart()
                p.runClean()
                assertEquals("local edit", p.local.content("doc.txt"))
                assertEquals("drive edit", p.local.content(copyOf("doc.txt")))
                p.assertMirrored()
                p.assertConverged()
            }
    }
}
