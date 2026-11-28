package io.github.drivesync.core.data

import io.github.drivesync.core.data.history.HistoryRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.store.BaseUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rewind's history: every base change, grouped by run, written with the change itself. */
class HistoryTest : DbTest() {
    private val pairs = PairRepository(db, { now }, { "p" })
    private val history = HistoryRepository(db)

    private fun entry(
        path: String,
        md5: String,
    ) = BaseEntry(RelPath.of(path), isFolder = false, md5 = md5, remoteFileId = "id-$path")

    private suspend fun run(
        at: Long,
        vararg updates: BaseUpdate,
    ) {
        now = at
        val store = pairs.baseStore("p")
        updates.forEach { store.commit(listOf(it)) }
    }

    private fun put(
        path: String,
        md5: String,
    ) = BaseUpdate.Put(entry(path, md5))

    private fun Map<RelPath, BaseEntry>.md5s() = entries.associate { (k, v) -> k.value to v.md5 }

    private suspend fun addPair() = pairs.add("p", "Notes", "content://tree", "drive")

    @Test
    fun `each run is a point`() =
        runTest {
            addPair()
            run(100, put("a.md", "1"), put("b.md", "1"))
            run(200, put("a.md", "2"), BaseUpdate.Remove(RelPath.of("b.md")))

            val timeline = history.timeline("p")

            assertEquals(listOf(100L, 200L), timeline.points)
            assertEquals(mapOf("a.md" to "1", "b.md" to "1"), timeline.stateAsOf(100).md5s())
            assertEquals(mapOf("a.md" to "2"), timeline.stateAsOf(200).md5s())
        }

    @Test
    fun `the first change saves a start`() =
        runTest {
            addPair()
            run(100, put("a.md", "1"))
            db.base().clearHistory("p") // As if the app was updated after this sync.

            run(200, put("b.md", "1"))

            val timeline = history.timeline("p")
            assertEquals(listOf(199L, 200L), timeline.points)
            assertEquals(mapOf("a.md" to "1"), timeline.stateAsOf(199).md5s())
        }

    @Test
    fun `empty commits add nothing`() =
        runTest {
            addPair()
            run(100)
            pairs.baseStore("p").commit(emptyList())
            assertTrue(history.timeline("p").isEmpty)
        }

    @Test
    fun `a reset starts over`() =
        runTest {
            addPair()
            run(100, put("a.md", "1"))
            pairs.mergeOnlyNextRun("p")
            assertTrue(history.timeline("p").isEmpty)
        }

    @Test
    fun `old points collapse`() =
        runTest {
            addPair()
            run(100, put("a.md", "1"), put("b.md", "1"))
            run(200, BaseUpdate.Remove(RelPath.of("b.md")))
            run(300, put("a.md", "3"))

            history.prune(before = 250)

            val timeline = history.timeline("p")
            assertEquals(listOf(200L, 300L), timeline.points)
            assertEquals(mapOf("a.md" to "1"), timeline.stateAsOf(200).md5s())
            assertEquals(mapOf("a.md" to "3"), timeline.stateAsOf(300).md5s())
        }

    @Test
    fun `removing a pair drops it`() =
        runTest {
            addPair()
            run(100, put("a.md", "1"))
            pairs.remove("p")
            assertEquals(emptyList<String>(), db.base().historyPairs())
        }
}
