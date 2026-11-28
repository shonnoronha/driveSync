package io.github.drivesync.feature.pairs

import io.github.drivesync.core.sync.model.BaseChange
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseTimeline
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.RewindChangeKind
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.feature.pairs.rewind.rewindState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RewindStateTest {
    private fun put(
        at: Long,
        path: String,
        md5: String,
    ) = BaseChange(at, RelPath.of(path), BaseEntry(RelPath.of(path), false, md5 = md5, remoteFileId = path))

    private val timeline =
        BaseTimeline(
            listOf(
                put(10, "a.md", "1"),
                put(20, "a.md", "2"),
                put(20, "b.md", "1"),
                put(30, "c.md", "1"),
            ),
        )

    @Test
    fun `starts at undo the last sync`() {
        val state = rewindState("Notes", timeline, null, running = false, result = null)
        assertEquals(listOf(1, 2, 1), state.points.map { it.changes })
        assertEquals(20L, state.selectedPoint?.time)
        assertEquals(1, state.files(RewindChangeKind.TO_TRASH))
        assertTrue(state.canRewind)
    }

    @Test
    fun `further back shows more`() {
        val state = rewindState("Notes", timeline, 10, running = false, result = null)
        assertEquals(1, state.files(RewindChangeKind.RESTORE))
        assertEquals(2, state.files(RewindChangeKind.TO_TRASH))
    }

    @Test
    fun `now and running cannot rewind`() {
        assertFalse(rewindState("Notes", timeline, 30, running = false, result = null).canRewind)
        assertFalse(rewindState("Notes", timeline, 10, running = true, result = null).canRewind)
        assertTrue(rewindState("Notes", BaseTimeline(emptyList()), null, false, null).points.isEmpty())
    }

    @Test
    fun `an empty point is refused`() {
        val fromNothing =
            BaseTimeline(
                listOf(BaseChange(5, RelPath.of("d"), BaseEntry(RelPath.of("d"), true))) + timeline.changesAll(),
            )
        val state = rewindState("Notes", fromNothing, 5, running = false, result = null)
        assertEquals(RewindRefusal.EMPTY_POINT, state.refusal)
        assertFalse(state.canRewind)
    }

    private fun BaseTimeline.changesAll() = points.flatMap { changesIn(it) }
}
