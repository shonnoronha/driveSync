package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-P5: excluded files never sync and, above all, a rule never deletes anything on either side. */
class FiltersTest {
    private fun rules(
        vararg exclude: String,
        maxFileSize: Long? = null,
        skipHidden: Boolean = false,
    ) = PairSettings(filters = FileFilters(exclude.toList(), maxFileSize, skipHidden))

    private fun Phone.assertNothingDeleted() {
        assertEquals(emptyList<String>(), remote.trashed(), "Drive trash")
        assertEquals(emptyMap<String, String>(), local.recycled(), "phone recycle folder")
    }

    @Test
    fun `excluded files are not uploaded`() =
        runTest {
            val settings = rules("*.tmp~", "cache")
            val p = Phone()
            p.local.write("a.md", "a")
            p.local.write("b.tmp~", "b")
            p.local.write("cache/x.bin", "x")

            p.runClean(settings)

            assertEquals(mapOf("a.md" to listOf("a")), p.remote.files())
            assertFalse("cache" in p.remote.folders())
            p.assertConverged(settings)
        }

    @Test
    fun `excluded Drive files are not downloaded`() =
        runTest {
            val settings = rules(skipHidden = true)
            val p = Phone()
            p.remote.write("a.md", "a")
            p.remote.write(".obsidian/workspace.json", "{}")

            p.runClean(settings)

            assertEquals(mapOf("a.md" to "a"), p.local.files())
            p.assertConverged(settings)
        }

    @Test
    fun `a new rule keeps synced files on both sides`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "a")
            p.local.write("logs/1.log", "l")
            p.runClean()
            val settings = rules("logs")

            p.local.delete("logs")
            p.runClean(settings)

            p.assertNothingDeleted()
            assertEquals(listOf("l"), p.remote.files()["logs/1.log"])
            assertTrue("logs/1.log" in p.base.paths(), "the base row is kept, not forgotten")
            p.assertConverged(settings)
        }

    @Test
    fun `a file growing past the limit is not trashed`() =
        runTest {
            val settings = rules(maxFileSize = 5)
            val p = Phone()
            p.local.write("clip.mp4", "small")
            p.runClean(settings)

            p.local.write("clip.mp4", "now much bigger")
            p.runClean(settings)

            p.assertNothingDeleted()
            assertEquals("small", p.remote.content("clip.mp4"))
            assertEquals("now much bigger", p.local.content("clip.mp4"))
            p.assertConverged(settings)
        }

    @Test
    fun `an excluded file keeps its folder on Drive`() =
        runTest {
            val settings = rules("*.raw")
            val p = Phone()
            p.remote.write("Photos/a.raw", "r")
            p.local.write("Photos/b.jpg", "b")
            p.local.write("keep.md", "k") // An empty phone folder would pause the pair (PRD 3.9 rule 1).
            p.runClean(settings)

            p.local.delete("Photos")
            p.runClean(settings)

            assertEquals(listOf("Photos/b.jpg"), p.remote.trashed())
            assertTrue("Photos" in p.remote.folders())
            assertEquals(listOf("r"), p.remote.files()["Photos/a.raw"])
        }

    @Test
    fun `removing a rule links files back`() =
        runTest {
            val settings = rules("*.bak")
            val p = Phone()
            p.local.write("a.bak", "same")
            p.remote.write("a.bak", "same")
            p.runClean(settings)

            val outcome = p.runClean()

            assertEquals(emptyList<Any>(), p.transfersOf("a.bak"))
            assertEquals(emptyList<Any>(), outcome.plan.conflicts)
            p.assertNothingDeleted()
            p.assertConverged()
        }

    @Test
    fun `a rule excluding everything does not pause`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "a")
            p.runClean()

            p.runClean(rules("*"))

            p.assertNothingDeleted()
        }

    @Test
    fun `download-only pairs follow the rules too`() =
        runTest {
            val settings = PairSettings(direction = SyncDirection.DOWNLOAD_ONLY, filters = FileFilters(listOf("*.iso")))
            val p = Phone()
            p.remote.write("big.iso", "iso")
            p.remote.write("a.md", "a")

            p.runClean(settings)

            assertEquals(mapOf("a.md" to "a"), p.local.files())
        }

    @Test
    fun `top level only`() =
        runTest {
            val p = Phone()
            p.local.write("a.md", "a")
            p.local.write("sub/b.md", "b")
            p.runClean()
            val topOnly = PairSettings(filters = FileFilters(includeSubfolders = false))

            p.local.write("sub/c.md", "c")
            p.local.write("d.md", "d")
            p.remote.write("sub/e.md", "e")
            p.remote.write("new/x.md", "x")
            p.runClean(topOnly)

            p.assertNothingDeleted()
            assertEquals(setOf("a.md", "d.md", "sub/b.md", "sub/e.md", "new/x.md"), p.remote.files().keys)
            assertEquals(setOf("a.md", "d.md", "sub/b.md", "sub/c.md"), p.local.files().keys)
            p.assertConverged(topOnly)

            // Back on: everything in the subfolders syncs, without duplicates.
            p.runClean()

            assertEquals(p.local.files().keys, p.remote.files().keys)
            assertTrue("new/x.md" in p.local.files())
            p.assertNothingDeleted()
            p.assertConverged()
        }
}
