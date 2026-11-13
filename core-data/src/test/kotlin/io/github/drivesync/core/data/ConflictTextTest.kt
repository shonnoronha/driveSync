package io.github.drivesync.core.data

import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.activity.SyncRecorder
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.engine.ExecutionReport
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SyncPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflictTextTest : DbTest() {
    @Test
    fun `every resolution reads as a sentence`() =
        runTest {
            PairRepository(db, { 0 }, { "p" }).add("p", "Notes", "t", "f")
            val conflicts =
                ConflictResolution.entries.mapIndexed {
                    i,
                    r,
                    ->
                    Conflict(RelPath.of("f$i"), ConflictKind.BOTH_MODIFIED, r)
                }
            SyncRecorder(db) {
                0
            }.record(
                "p",
                SyncOutcome.Completed(SyncPlan(emptyList(), conflicts, emptyList()), ExecutionReport(emptyList())),
            )
            val details =
                ActivityRepository(db)
                    .observe("p")
                    .first()
                    .mapNotNull { it.detail }
                    .toSet()
            assertEquals(ConflictResolution.entries.size, details.size)
        }
}
