package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.path
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.SkipReason
import io.github.drivesync.core.sync.plan.Skipped
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** PRD 3.9 rules 1, 9, 10 and Google-native files (FR-S12). */
class DeleteSafetyScenarioTest {
    private suspend fun syncedPhone(vararg files: Pair<String, String>): Phone {
        val p = Phone()
        files.forEach { (path, content) -> p.local.write(path, content) }
        p.runClean()
        return p
    }

    @Nested
    inner class TrashAndRecycle {
        @Test
        fun `local delete moves the Drive file to trash`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                p.local.delete("a.txt")

                p.runClean()

                assertEquals(listOf("a.txt"), p.remote.trashed())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `Drive delete moves the local file to the recycle folder`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                p.remote.trashAt("a.txt")

                p.runClean()

                assertEquals(mapOf("a.txt" to "A"), p.local.recycled())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local folder delete trashes its files and the folder on Drive`() =
            runTest {
                val p = syncedPhone("Trip/a.jpg" to "a", "Trip/day2/b.jpg" to "b", "keep.txt" to "k")
                p.local.delete("Trip")

                p.runClean()

                assertEquals(setOf("Trip/a.jpg", "Trip/day2/b.jpg", "Trip/day2", "Trip"), p.remote.trashed().toSet())
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `REMOTE_WINS keeps the overwritten unsynced local edit in the recycle folder`() =
            runTest {
                val settings = PairSettings(conflictPolicy = ConflictPolicy.REMOTE_WINS)
                val p = syncedPhone("a.txt" to "v1")
                p.local.write("a.txt", "mine")
                p.remote.write("a.txt", "theirs")

                p.runClean(settings)

                assertEquals("theirs", p.local.content("a.txt"))
                assertEquals(mapOf("a.txt" to "mine"), p.local.recycled())
                p.assertMirrored()
                p.assertConverged(settings)
            }

        @Test
        fun `a plain download over an unchanged synced file does not fill the recycle folder`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.write("a.txt", "v2")

                p.runClean()

                assertEquals("v2", p.local.content("a.txt"))
                assertEquals(emptyMap<String, String>(), p.local.recycled())
                p.assertConverged()
            }
    }

    @Nested
    inner class UnavailableOrEmpty {
        private suspend fun assertPausedWithoutWrites(
            p: Phone,
            reason: PauseReason,
        ) {
            val commits = p.base.commits
            val localBefore = p.local.files()
            val remoteBefore = p.remote.files()

            val outcome = p.sync()

            assertEquals(SyncOutcome.Paused(reason), outcome)
            assertEquals(emptyList<String>(), p.writes())
            assertEquals(commits, p.base.commits)
            assertEquals(localBefore, p.local.files())
            assertEquals(remoteBefore, p.remote.files())
            assertEquals(emptyMap<String, String>(), p.local.recycled())
        }

        @Test
        fun `unreachable local folder pauses without writing anything`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.local.unavailableReason = "SD card removed"
                p.remote.write("a.txt", "drive edit")

                assertPausedWithoutWrites(p, PauseReason.LocalUnreachable("SD card removed"))
                assertEquals(emptyList<String>(), p.remote.trashed())

                p.local.unavailableReason = null
                p.runClean()
                assertEquals("drive edit", p.local.content("a.txt"))
                p.assertConverged()
            }

        @Test
        fun `unreachable Drive folder pauses without writing anything`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                p.remote.unavailableReason = "pair folder trashed"

                assertPausedWithoutWrites(p, PauseReason.RemoteUnreachable("pair folder trashed"))

                p.remote.unavailableReason = null
                p.assertConverged()
            }

        @Test
        fun `suddenly empty local folder pauses instead of trashing Drive`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "dir/b.txt" to "B")
                p.local.delete("a.txt")
                p.local.delete("dir/b.txt")

                assertPausedWithoutWrites(p, PauseReason.LocalSuddenlyEmpty)
                assertEquals(emptyList<String>(), p.remote.trashed())
            }

        @Test
        fun `suddenly empty Drive folder pauses instead of recycling local files`() =
            runTest {
                val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
                p.remote.trashAt("a.txt")
                p.remote.trashAt("b.txt")

                assertPausedWithoutWrites(p, PauseReason.RemoteSuddenlyEmpty)
                assertEquals(mapOf("a.txt" to "A", "b.txt" to "B"), p.local.files())
            }

        @Test
        fun `a Google Doc does not count as a file when Drive is suddenly empty`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.googleDoc("Notes")
                p.remote.trashAt("a.txt")

                assertPausedWithoutWrites(p, PauseReason.RemoteSuddenlyEmpty)
            }
    }

    @Nested
    inner class MassDeleteGuard {
        @Test
        fun `mass delete asks first and runs only after confirmation`() =
            runTest {
                val names = (0 until 30).map { "f%02d.txt".format(it) }
                val p = syncedPhone(*names.map { it to "content $it" }.toTypedArray())
                names.take(25).forEach(p.local::delete)
                val commits = p.base.commits

                val outcome = p.sync()

                assertEquals(SyncOutcome.NeedsDeleteConfirmation(deleteCount = 25, pairFileCount = 30), outcome)
                assertEquals(emptyList<String>(), p.writes())
                assertEquals(commits, p.base.commits)
                assertEquals(30, p.remote.files().size)

                val confirmed = assertInstanceOf(SyncOutcome.Completed::class.java, p.sync(confirm = true))
                assertTrue(confirmed.report.allDone)
                assertEquals(names.take(25).toSet(), p.remote.trashed().toSet())
                p.assertMirrored()
                p.assertConverged()
            }
    }

    @Nested
    inner class GoogleDocs {
        @Test
        fun `Google Docs are never downloaded, trashed or tracked`() =
            runTest {
                val p = Phone()
                p.local.write("a.txt", "A")
                p.remote.googleDoc("Meeting notes")

                val outcome = p.runClean()

                assertTrue(Skipped(path("Meeting notes"), SkipReason.GOOGLE_NATIVE) in outcome.plan.skipped)
                assertEquals(emptyList<RemoteOp>(), p.transfersOf("Meeting notes"))
                assertFalse(p.local.exists("Meeting notes"))
                assertFalse("Meeting notes" in p.base.paths())
                p.assertConverged()
                assertEquals(emptyList<String>(), p.remote.trashed())
            }

        @Test
        fun `local folder delete keeps a Drive folder that holds a Google Doc`() =
            runTest {
                val p = syncedPhone("Shared/a.txt" to "A", "keep.txt" to "k")
                p.remote.googleDoc("Shared/Plan")
                p.local.delete("Shared")

                val outcome = p.runClean()

                assertEquals(listOf("Shared/a.txt"), p.remote.trashed())
                assertTrue("Shared" in p.remote.folders())
                assertFalse(outcome.plan.actions.any { it is SyncAction.TrashRemote && it.path.value == "Shared" })
                p.assertMirrored()
                p.assertConverged()
            }
    }
}
