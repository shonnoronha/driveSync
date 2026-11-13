package io.github.drivesync.core.data

import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.activity.SyncRecorder
import io.github.drivesync.core.data.db.ActivityKind
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.engine.SyncEngine
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.fakes.FakeLocalStore
import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.SkipReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

/** The recorder fed by real engine runs over the Room base store. */
class SyncRecorderTest : DbTest() {
    private val pairs = PairRepository(db, { now }, { "p" })
    private val recorder = SyncRecorder(db) { now }
    private val activity = ActivityRepository(db)
    private val local = FakeLocalStore()
    private val drive = FakeRemoteStore()
    private val stamp = ConflictStamp(LocalDateTime.of(2026, 11, 13, 9, 30), "Pixel")

    private suspend fun pairId() = pairs.add("p", "Notes", "tree", "folder").id

    private suspend fun run(settings: PairSettings = PairSettings()): SyncOutcome {
        val outcome = SyncEngine(local, drive, pairs.baseStore("p")).sync(settings, stamp)
        recorder.record("p", outcome)
        return outcome
    }

    private suspend fun kinds() = activity.observe("p").first().map { it.kind }

    @Test
    fun `logs transfers and marks the pair idle`() =
        runTest {
            pairId()
            local.write("a.md", "a")
            drive.write("d/b.md", "b")
            drive.googleDoc("Plan")
            now = 5_000
            run()
            assertEquals(
                setOf(ActivityKind.UPLOADED, ActivityKind.DOWNLOADED, ActivityKind.FOLDER_CREATED),
                kinds().toSet(),
            )
            val pair = pairs.pair("p")!!
            assertEquals(PairStatus.IDLE, pair.status)
            assertEquals(5_000L, pair.lastSyncAt)
            assertEquals(
                SkipReason.GOOGLE_NATIVE,
                activity
                    .observeSkipped("p")
                    .first()
                    .single()
                    .reason,
            )
        }

    @Test
    fun `the base survives an app restart`() =
        runTest {
            pairId()
            local.write("a.md", "a")
            run()
            // A new engine and store, as after the process was killed: nothing left to do, no deletes.
            val again = run() as SyncOutcome.Completed
            assertEquals(emptyList<Any>(), again.plan.actions)
        }

    @Test
    fun `logs moves, deletes and conflicts`() =
        runTest {
            pairId()
            local.write("a.md", "a")
            local.write("b.md", "b")
            local.write("c.md", "c")
            run()
            local.rename("a.md", "moved.md")
            local.delete("b.md")
            local.write("c.md", "phone")
            drive.write("c.md", "drive")
            run()
            val kinds = kinds()
            assertTrue(
                kinds.toString(),
                kinds.containsAll(listOf(ActivityKind.MOVED, ActivityKind.TRASHED_ON_DRIVE, ActivityKind.CONFLICT)),
            )
            val conflict = activity.observeConflicts().first().single()
            assertEquals(ConflictKind.BOTH_MODIFIED, conflict.kind)
        }

    @Test
    fun `logs remote deletes and recycles`() =
        runTest {
            pairId()
            drive.write("x.md", "x")
            drive.write("keep.md", "k") // Trashing the only file would pause the pair (rule 1).
            run()
            drive.trashAt("x.md")
            run()
            assertTrue(ActivityKind.RECYCLED_ON_PHONE in kinds())
        }

    @Test
    fun `pending conflicts are replaced each run`() =
        runTest {
            pairId()
            local.write("c.md", "phone")
            drive.write("c.md", "drive")
            run(PairSettings(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(1, activity.observeConflicts().first().size)
            local.write("c.md", "drive")
            run(PairSettings(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(emptyList<Any>(), activity.observeConflicts().first())
        }

    @Test
    fun `failed actions set the error status`() =
        runTest {
            pairId()
            local.write("a.md", "a")
            local.beforeOp = { op, _ -> if (op == LocalOp.READ) throw IOException("disk") }
            run()
            assertEquals(PairStatus.ERROR, pairs.pair("p")!!.status)
            assertTrue(ActivityKind.FAILED in kinds())
        }

    @Test
    fun `a pause is logged once`() =
        runTest {
            pairId()
            local.unavailableReason = "SD card removed"
            run()
            run()
            assertEquals(listOf(ActivityKind.PAUSED), kinds())
            val pair = pairs.pair("p")!!
            assertEquals(PairStatus.NEEDS_ATTENTION, pair.status)
            assertTrue(pair.statusDetail!!.contains("SD card removed"))
        }

    @Test
    fun `every pause reason is described`() =
        runTest {
            pairId()
            val reasons =
                listOf(
                    PauseReason.LocalUnreachable("l"),
                    PauseReason.RemoteUnreachable("r"),
                    PauseReason.LocalSuddenlyEmpty,
                    PauseReason.RemoteSuddenlyEmpty,
                )
            val details =
                reasons.map {
                    recorder.record("p", SyncOutcome.Paused(it))
                    pairs.pair("p")!!.statusDetail
                }
            assertEquals(4, details.toSet().size)
        }

    @Test
    fun `a mass delete waits for confirmation`() =
        runTest {
            pairId()
            recorder.record("p", SyncOutcome.NeedsDeleteConfirmation(30, 100))
            val pair = pairs.pair("p")!!
            assertEquals(PairStatus.WAITING_FOR_CONFIRMATION, pair.status)
            assertEquals("30 of 100 files would be deleted", pair.statusDetail)
        }

    @Test
    fun `run failures pause only when the user must act`() =
        runTest {
            pairId()
            recorder.recordFailure("p", "offline", needsUser = false)
            assertEquals(PairStatus.IDLE, pairs.pair("p")!!.status)
            recorder.recordFailure("p", "sign in again", needsUser = true)
            assertEquals(PairStatus.NEEDS_ATTENTION, pairs.pair("p")!!.status)
            assertEquals(listOf(ActivityKind.PAUSED, ActivityKind.FAILED), kinds())
            recorder.record("missing", SyncOutcome.Paused(PauseReason.LocalSuddenlyEmpty)) // No-op.
        }

    @Test
    fun `old log entries are pruned`() =
        runTest {
            pairId()
            recorder.recordFailure("p", "old", needsUser = false)
            now = 10_000
            recorder.recordFailure("p", "new", needsUser = false)
            assertEquals(1, activity.prune(before = 5_000))
            assertEquals(listOf("new"), activity.observe().first().map { it.detail })
        }

    @Test
    fun `the default constructor works`() {
        SyncRecorder(db)
    }
}
