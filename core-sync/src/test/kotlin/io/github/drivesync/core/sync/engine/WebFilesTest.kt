package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.path
import io.github.drivesync.core.sync.plan.SkipReason
import io.github.drivesync.core.sync.plan.Skipped
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Full Drive access (PRD 6.4): files added to the pair folder from the Drive website or other apps become visible
 * with no base entry. They must be downloaded or merged, never cause a delete on either side. Turning the mode off
 * hides them again, which is only safe with a base reset (merge-only run, PRD 3.9 rule 7).
 */
class WebFilesTest {
    private suspend fun syncedPhone(vararg files: Pair<String, String>): Phone {
        val p = Phone()
        files.forEach { (path, content) -> p.local.write(path, content) }
        p.runClean()
        return p
    }

    private fun Phone.assertNothingDeleted() {
        assertEquals(emptyList<String>(), remote.trashed(), "Drive trash")
        assertEquals(emptyMap<String, String>(), local.recycled(), "phone recycle folder")
    }

    @Nested
    inner class Appear {
        @Test
        fun `website file is downloaded`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.write("web.pdf", "W")

                p.runClean()

                assertEquals("W", p.local.content("web.pdf"))
                assertTrue("web.pdf" in p.base.paths())
                p.assertNothingDeleted()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `download-only pair pulls website file`() =
            runTest {
                val settings = PairSettings(direction = SyncDirection.DOWNLOAD_ONLY)
                val p = Phone()
                p.remote.write("a.txt", "A")
                p.runClean(settings)
                p.remote.write("web.pdf", "W")

                p.runClean(settings)

                assertEquals("W", p.local.content("web.pdf"))
                p.assertNothingDeleted()
                p.assertConverged(settings)
            }

        @Test
        fun `upload-only pair leaves website file`() =
            runTest {
                val settings = PairSettings(direction = SyncDirection.UPLOAD_ONLY)
                val p = Phone()
                p.local.write("a.txt", "A")
                p.runClean(settings)
                p.remote.write("web.pdf", "W")

                p.runClean(settings)

                assertEquals(null, p.local.content("web.pdf"))
                assertEquals("W", p.remote.content("web.pdf"))
                assertEquals(emptyList<String>(), p.writes())
                p.assertNothingDeleted()
            }

        @Test
        fun `website folder tree is downloaded`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.write("Trip/day1/beach.jpg", "b")
                p.remote.write("Trip/map.png", "m")
                p.remote.mkdir("Empty")

                p.runClean()

                assertEquals("b", p.local.content("Trip/day1/beach.jpg"))
                assertEquals("m", p.local.content("Trip/map.png"))
                assertTrue(p.local.folders().containsAll(setOf("Trip", "Trip/day1", "Empty")))
                p.assertNothingDeleted()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `many website files never delete`() =
            runTest {
                val p = syncedPhone(*Array(40) { "phone$it.txt" to "p$it" })
                repeat(30) { p.remote.write("web$it.txt", "w$it") }

                val outcome = p.runClean()

                assertEquals(0, outcome.plan.deleteCount)
                assertEquals(70, p.local.files().size)
                p.assertNothingDeleted()
                p.assertConverged()
            }
    }

    @Nested
    inner class SameName {
        @Test
        fun `same content on both sides is linked`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.local.write("n.txt", "same")
                p.remote.write("n.txt", "same")

                val outcome = p.runClean()

                assertTrue(outcome.plan.actions.any { it is SyncAction.Link && it.path.value == "n.txt" })
                assertEquals(emptyList<Any>(), p.transfersOf("n.txt"))
                p.assertNothingDeleted()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `different content keeps both`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.local.write("n.txt", "phone")
                p.remote.write("n.txt", "web")

                val outcome = p.runClean()

                assertEquals(1, outcome.plan.conflicts.size)
                val bothVersions = setOf("phone", "web")
                assertEquals(bothVersions, setOf(p.local.content("n.txt"), p.local.content(copyOf("n.txt"))))
                p.assertNothingDeleted()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `website twin of a synced file is kept`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.writeDuplicate("a.txt", "W")

                p.runClean()

                val phoneContents = p.local.files().values
                val driveContents =
                    p.remote
                        .files()
                        .values
                        .flatten()
                assertEquals(setOf("A", "W"), phoneContents.toSet())
                assertEquals(setOf("A", "W"), driveContents.toSet())
                p.assertNothingDeleted()
                p.assertConverged()
            }
    }

    @Nested
    inner class GoogleNative {
        @Test
        fun `website Google Doc is skipped`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.googleDoc("Docs/plan")

                val outcome = p.runClean()

                assertTrue(Skipped(path("Docs/plan"), SkipReason.GOOGLE_NATIVE) in outcome.plan.skipped)
                assertTrue(p.local.exists("Docs"))
                p.assertNothingDeleted()
            }

        @Test
        fun `Google Doc keeps its Drive folder`() =
            runTest {
                val p = syncedPhone("a.txt" to "A")
                p.remote.googleDoc("Docs/plan")
                p.runClean()
                p.local.delete("Docs")

                p.runClean()

                assertEquals(emptyList<String>(), p.remote.trashed())
                assertTrue("Docs" in p.remote.folders())
            }
    }

    @Nested
    inner class TurnedOff {
        /** Two phone files, then three website files downloaded while full access was on. */
        private suspend fun phoneWithWebFiles(): Phone {
            val p = syncedPhone("a.txt" to "A", "b.txt" to "B")
            repeat(3) { p.remote.write("web$it.txt", "w$it") }
            p.runClean()
            return p
        }

        @Test
        fun `hidden files are recycled without reset`() =
            runTest {
                // Why turning the mode off must reset the base: hidden reads as deleted on Drive.
                val p = phoneWithWebFiles()
                repeat(3) { p.remote.hideFromApp("web$it.txt") }

                p.runClean()

                assertEquals(setOf("web0.txt", "web1.txt", "web2.txt"), p.local.recycled().keys)
            }

        @Test
        fun `base reset after turning off deletes nothing`() =
            runTest {
                val p = phoneWithWebFiles()
                val before = p.local.files()
                repeat(3) { p.remote.hideFromApp("web$it.txt") }
                p.base.clear()

                p.runClean()

                p.assertNothingDeleted()
                assertEquals(before, p.local.files())
                // The app can't see the originals, so it uploads its own copies next to them.
                repeat(3) { assertEquals(listOf("w$it", "w$it"), p.remote.files()["web$it.txt"]) }
                assertEquals(listOf("A"), p.remote.files()["a.txt"])
                p.assertConverged()
            }
    }
}
