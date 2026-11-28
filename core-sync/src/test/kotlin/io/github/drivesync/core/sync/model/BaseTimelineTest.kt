package io.github.drivesync.core.sync.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaseTimelineTest {
    private fun file(
        path: String,
        md5: String,
    ) = BaseEntry(RelPath.of(path), isFolder = false, md5 = md5, remoteFileId = "id-$path")

    private fun put(
        runAt: Long,
        path: String,
        md5: String,
    ) = BaseChange(runAt, RelPath.of(path), file(path, md5))

    private fun gone(
        runAt: Long,
        path: String,
    ) = BaseChange(runAt, RelPath.of(path), null)

    private val timeline =
        BaseTimeline(
            listOf(
                put(10, "a.md", "1"),
                put(10, "b.md", "1"),
                put(20, "a.md", "2"),
                gone(30, "b.md"),
                put(30, "c.md", "1"),
                // Within one run, the order of changes counts.
                gone(40, "c.md"),
                put(40, "c.md", "2"),
            ),
        )

    private fun Map<RelPath, BaseEntry>.md5s() = entries.associate { (k, v) -> k.value to v.md5 }

    @Test
    fun `points are the runs, oldest first`() {
        assertEquals(listOf(10L, 20L, 30L, 40L), timeline.points)
        assertTrue(BaseTimeline(emptyList()).isEmpty)
    }

    @Test
    fun `state as of a run`() {
        assertEquals(mapOf("a.md" to "1", "b.md" to "1"), timeline.stateAsOf(10).md5s())
        assertEquals(mapOf("a.md" to "2", "c.md" to "1"), timeline.stateAsOf(30).md5s())
        assertEquals(mapOf("a.md" to "2", "c.md" to "2"), timeline.stateAsOf(99).md5s())
    }

    @Test
    fun `state before a run`() {
        assertEquals(mapOf("a.md" to "2", "b.md" to "1"), timeline.stateBefore(30).md5s())
        assertEquals(emptyMap<String, String>(), timeline.stateBefore(10).md5s())
    }

    @Test
    fun `finds the run of a time`() {
        assertEquals(20L, timeline.pointAtOrBefore(25))
        assertEquals(40L, timeline.pointAtOrBefore(40))
        assertNull(timeline.pointAtOrBefore(5))
        assertEquals(2, timeline.changesIn(40).size)
    }
}
