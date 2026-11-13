package io.github.drivesync.feature.conflicts

import io.github.drivesync.core.data.db.ConflictEntity
import io.github.drivesync.core.data.db.ResolutionEntity
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConflictsStateTest {
    private fun conflict(
        path: String,
        kind: ConflictKind,
        resolution: ConflictResolution,
    ) = ConflictEntity("p", path, kind, resolution, null, 0)

    @Test
    fun `splits pending from resolved and attaches answers`() {
        val state =
            conflictsState(
                listOf(
                    conflict("a", ConflictKind.BOTH_MODIFIED, ConflictResolution.PENDING),
                    conflict("b", ConflictKind.BOTH_NEW, ConflictResolution.KEPT_BOTH),
                ),
                listOf(ResolutionEntity("p", "a", ConflictPolicy.REMOTE_WINS)),
                mapOf("p" to "Notes"),
            )
        assertEquals(listOf("a"), state.pending.map { it.path })
        assertEquals(ConflictPolicy.REMOTE_WINS, state.pending.single().chosen)
        assertEquals("Notes", state.pending.single().pairName)
        assertEquals(listOf("b"), state.resolved.map { it.path })
    }

    @Test
    fun `choices depend on the kind`() {
        fun choices(kind: ConflictKind) =
            conflictsState(
                listOf(conflict("x", kind, ConflictResolution.PENDING)),
                emptyList(),
                emptyMap(),
            ).pending.single().choices
        assertEquals(3, choices(ConflictKind.BOTH_MODIFIED).size)
        assertEquals(3, choices(ConflictKind.DESTINATION_MODIFIED).size)
        assertEquals(listOf(ConflictPolicy.KEEP_BOTH), choices(ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED))
        assertEquals(listOf(ConflictPolicy.KEEP_BOTH), choices(ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED))
        assertEquals(emptyList<ConflictPolicy>(), choices(ConflictKind.TYPE_MISMATCH))
        assertEquals(emptyList<ConflictPolicy>(), choices(ConflictKind.DUPLICATE_REMOTE_NAME))
    }
}
