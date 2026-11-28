package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.sync.plan.RewindSkipReason
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/** Rewind end to end: the folder goes back to an earlier point on both sides, and nothing is lost on the way. */
class RewindTest {
    private suspend fun Phone.rewindClean(target: Map<RelPath, BaseEntry>): RewindOutcome.Completed {
        val outcome = assertInstanceOf(RewindOutcome.Completed::class.java, rewind(target))
        assertEquals(emptyList<ActionResult>(), outcome.report.results.filter { it.outcome != ActionOutcome.Done })
        return outcome
    }

    @Test
    fun `an edit is undone`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "v1")
            p.runClean()
            val before = p.point()
            val driveId = p.remote.fileId("a.md")
            p.local.write("a.md", "v2")
            p.runClean()
            val after = p.point()

            p.rewindClean(before)

            assertEquals("v1", p.local.content("a.md"))
            assertEquals("v1", p.remote.content("a.md"))
            assertEquals(driveId, p.remote.fileId("a.md"), "same Drive file, new version")
            p.assertConverged()

            // The rewind is a point too: going forward again brings v2 back.
            p.rewindClean(after)
            assertEquals("v2", p.local.content("a.md"))
            assertEquals("v2", p.remote.content("a.md"))
            p.assertConverged()
        }

    @Test
    fun `a deleted file comes back`() =
        runTest {
            val p = Phone()
            p.local.write("notes/a.md", "a")
            p.local.write("notes/b.md", "b")
            p.local.write("keep.md", "k") // A phone with no files at all would pause the sync (rule 1).
            p.runClean()
            val before = p.point()
            p.local.delete("notes")
            p.runClean()
            assertEquals(mapOf("keep.md" to "k"), p.local.files())

            p.rewindClean(before)

            assertEquals(mapOf("keep.md" to "k", "notes/a.md" to "a", "notes/b.md" to "b"), p.local.files())
            assertEquals(setOf("keep.md", "notes/a.md", "notes/b.md"), p.remote.files().keys)
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `newer files go to the trash`() =
        runTest {
            val p = Phone()
            p.local.write("keep.md", "k")
            p.runClean()
            val before = p.point()
            p.remote.write("new/x.md", "x")
            p.local.write("y.md", "y")
            p.runClean()

            p.rewindClean(before)

            assertEquals(mapOf("keep.md" to "k"), p.local.files())
            assertEquals(setOf("keep.md"), p.remote.files().keys)
            assertTrue("new/x.md" in p.remote.trashed() && "y.md" in p.remote.trashed())
            assertEquals(setOf("new/x.md", "y.md"), p.local.recycled().keys)
            p.assertConverged()
        }

    @Test
    fun `unsynced edits are never overwritten`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "v1")
            p.runClean()
            val before = p.point()
            p.local.write("a.md", "v2")
            p.runClean()
            p.local.write("a.md", "v3 not synced yet")

            val outcome = p.rewindClean(before)

            assertEquals(
                RewindSkipReason.CHANGED_SINCE_SYNC,
                outcome.plan.skipped
                    .single()
                    .reason,
            )
            assertEquals("v3 not synced yet", p.local.content("a.md"))
            assertEquals("v2", p.remote.content("a.md"))
        }

    @Test
    fun `a forgotten version is skipped`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "v1")
            p.runClean()
            val before = p.point()
            p.local.write("a.md", "v2")
            p.runClean()
            p.remote.forgetOldVersions(p.remote.fileId("a.md"))

            val outcome = p.rewindClean(before)

            assertEquals(
                RewindSkipReason.VERSION_GONE,
                outcome.plan.skipped
                    .single()
                    .reason,
            )
            assertEquals("v2", p.local.content("a.md"))
        }

    @Test
    fun `an empty point is refused`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "a")
            p.runClean()
            val outcome = p.rewind(emptyMap())
            assertEquals(RewindOutcome.Refused(RewindRefusal.EMPTY_POINT), outcome)
            assertEquals("a", p.remote.content("a.md"))
        }

    @Test
    fun `a corrupt download changes nothing`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "v1")
            p.runClean()
            val before = p.point()
            p.local.write("a.md", "v2")
            p.runClean()
            p.remote.downloadTransform = { it + 0 }

            val outcome = assertInstanceOf(RewindOutcome.Completed::class.java, p.rewind(before))

            assertInstanceOf(
                ActionOutcome.Failed::class.java,
                outcome.report.results
                    .single()
                    .outcome,
            )
            assertEquals("v2", p.local.content("a.md"))
            assertEquals("v2", p.remote.content("a.md"))
            p.assertNoTempFiles()
        }

    @Test
    fun `a crash mid-restore heals`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "v1")
            p.runClean()
            val before = p.point()
            p.local.write("a.md", "v2")
            p.runClean()
            p.onLocal = { op, _ -> if (op == LocalOp.COMMIT_TEMP) killed() }

            val error = runCatching { p.rewind(before) }.exceptionOrNull()
            assertInstanceOf(CancellationException::class.java, error)
            assertEquals("v1", p.remote.content("a.md"), "Drive got the old version first")

            p.restart()
            val next = p.runClean()

            assertInstanceOf(SyncAction.Download::class.java, next.plan.actions.single())
            assertEquals("v1", p.local.content("a.md"))
            p.assertNoTempFiles()
            p.assertConverged()
        }
}
