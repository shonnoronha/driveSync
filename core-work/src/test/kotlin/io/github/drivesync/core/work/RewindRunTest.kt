package io.github.drivesync.core.work

import io.github.drivesync.core.data.db.ActivityKind
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.work.android.RewindWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.ZoneId

/** Rewind through the coordinator: Room history, the per-pair lock, the activity log. */
@RunWith(RobolectricTestRunner::class)
class RewindRunTest {
    private fun test(block: suspend TestScope.(WorkHarness) -> Unit) =
        runTest {
            val h = WorkHarness(backgroundScope)
            try {
                block(h)
            } finally {
                h.close()
            }
        }

    @Test
    fun `rewinds to a run`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            val phone = h.locals.getValue(id)
            phone.write("a.md", "v1")
            phone.write("keep.md", "k")
            // Run times near the harness date, so pruning (30 days) keeps them.
            val t =
                h.now
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            h.clock = t
            h.coordinator.syncAll()
            phone.write("a.md", "v2")
            h.clock = t + 1
            h.coordinator.syncAll()
            assertEquals(listOf(t, t + 1), h.history.timeline(id).points)

            h.clock = t + 2
            val result = h.coordinator.rewind(id, t)

            assertEquals(RewindResult.Done(changed = 1, skipped = 0, failed = 0), result)
            assertEquals("v1", phone.content("a.md"))
            assertEquals("v1", h.drives.getValue(id).content("a.md"))
            assertEquals("the rewind is a point too", listOf(t, t + 1, t + 2), h.history.timeline(id).points)
            val kinds =
                h.activity
                    .observe(id)
                    .first()
                    .map { it.kind }
            assertTrue(ActivityKind.RESTORED in kinds)
        }

    @Test
    fun `a pruned point is refused`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.locals.getValue(id).write("a.md", "v1")
            h.coordinator.syncAll()
            assertEquals(RewindResult.Refused(RewindRefusal.POINT_GONE), h.coordinator.rewind(id, 42))
        }

    @Test
    fun `network trouble asks for a retry`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.locals.getValue(id).write("a.md", "v1")
            h.coordinator.syncAll()
            h.remotes.getValue(id).error = IOException("offline")
            val point =
                h.history
                    .timeline(id)
                    .points
                    .single()
            assertTrue(h.coordinator.rewind(id, point) is RewindResult.Retry)
        }

    @Test
    fun `results survive as work data`() {
        val done = RewindResult.Done(3, 1, 0)
        assertEquals(done, RewindWork.read(RewindWork.output(done)))
        val refused = RewindResult.Refused(RewindRefusal.EMPTY_POINT)
        assertEquals(refused, RewindWork.read(RewindWork.output(refused)))
    }
}
