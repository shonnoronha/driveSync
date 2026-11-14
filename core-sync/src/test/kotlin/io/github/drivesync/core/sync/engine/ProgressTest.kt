package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.FakeLocalStore
import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.fakes.InMemoryBaseStore
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.testStamp
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressTest {
    @Test
    fun `reports checking, then each file operation`() =
        runTest {
            val local = FakeLocalStore()
            local.write("a.md", "a")
            local.write("d/b.md", "b")
            val updates = mutableListOf<SyncProgress>()
            SyncEngine(
                local,
                FakeRemoteStore(),
                InMemoryBaseStore(),
            ).sync(PairSettings(), testStamp, progress = updates::add)
            // One folder create and two uploads.
            assertEquals(
                listOf(
                    SyncProgress.Checking,
                    SyncProgress.Transferring(0, 3),
                    SyncProgress.Transferring(1, 3),
                    SyncProgress.Transferring(2, 3),
                    SyncProgress.Transferring(3, 3),
                ),
                updates,
            )
        }

    @Test
    fun `bookkeeping is not counted, and a paused run only checks`() =
        runTest {
            val local = FakeLocalStore()
            local.write("a.md", "same")
            val remote = FakeRemoteStore()
            remote.write("a.md", "same") // First sync links it: base bookkeeping only.
            val updates = mutableListOf<SyncProgress>()
            SyncEngine(local, remote, InMemoryBaseStore()).sync(PairSettings(), testStamp, progress = updates::add)
            assertEquals(listOf(SyncProgress.Checking, SyncProgress.Transferring(0, 0)), updates)

            local.unavailableReason = "gone"
            val paused = mutableListOf<SyncProgress>()
            SyncEngine(local, remote, InMemoryBaseStore()).sync(PairSettings(), testStamp, progress = paused::add)
            assertEquals(listOf<SyncProgress>(SyncProgress.Checking), paused)
        }
}
