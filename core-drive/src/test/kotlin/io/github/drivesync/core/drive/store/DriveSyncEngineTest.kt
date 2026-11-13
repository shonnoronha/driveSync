package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.drive.folders.DriveSyncFolders
import io.github.drivesync.core.sync.engine.ActionOutcome
import io.github.drivesync.core.sync.engine.SyncEngine
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.fakes.FakeLocalStore
import io.github.drivesync.core.sync.fakes.InMemoryBaseStore
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.PauseReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The core-sync engine against the real Drive client over HTTP: what step 3 must not break. */
class DriveSyncEngineTest {
    private val drive = FakeDriveServer()
    private val folders = DriveSyncFolders(drive.client())

    private inner class Phone(
        pairFolderId: String,
        val name: String,
    ) {
        val local = FakeLocalStore()
        private val engine = SyncEngine(local, DriveRemoteStore(drive.client(), pairFolderId), InMemoryBaseStore())

        suspend fun sync(): SyncOutcome =
            engine.sync(
                io.github.drivesync.core.sync.model
                    .PairSettings(),
                ConflictStamp(LocalDateTime.of(2026, 11, 13, 9, 30), name),
            )

        /** Syncs, requires every action to succeed, and checks that a second run has nothing left to do. */
        suspend fun syncClean() {
            val first = assertInstanceOf(SyncOutcome.Completed::class.java, sync())
            assertEquals(emptyList<Any>(), first.report.results.filter { it.outcome != ActionOutcome.Done })
            val second = assertInstanceOf(SyncOutcome.Completed::class.java, sync())
            assertEquals(emptyList<Any>(), second.plan.actions, "not converged")
        }
    }

    @AfterEach
    fun tearDown() = drive.close()

    private suspend fun newPair(): String =
        folders.createPairFolder(folders.findOrCreateRoot(), "Notes", "pair-1").folderId

    @Test
    fun `first sync uploads the phone's folder tree`() =
        runTest {
            val pair = newPair()
            val phone = Phone(pair, "Pixel")
            phone.local.write("a.md", "alpha")
            phone.local.write("2026/nov/b.md", "beta")
            phone.local.mkdir("empty")
            phone.syncClean()
            assertEquals(mapOf("2026/nov/b.md" to "beta", "a.md" to "alpha"), drive.tree(pair))
            assertTrue(drive.children(pair).any { it.name == "empty" && it.isFolder })
        }

    @Test
    fun `edits, moves and deletes flow from one phone to another through Drive`() =
        runTest {
            val pair = newPair()
            val a = Phone(pair, "A")
            val b = Phone(pair, "B")
            a.local.write("notes/todo.md", "v1")
            a.local.write("keep.md", "k")
            a.local.write("old.md", "bye")
            a.syncClean()
            b.syncClean()
            assertEquals(a.local.files(), b.local.files())
            val keepId = drive.children(pair).single { it.name == "keep.md" }.id

            b.local.write("notes/todo.md", "v2")
            b.local.rename("keep.md", "archive/keep.md")
            b.local.delete("old.md")
            b.syncClean()
            a.syncClean()

            val expected = mapOf("archive/keep.md" to "k", "notes/todo.md" to "v2")
            assertEquals(expected, a.local.files())
            assertEquals(expected, drive.tree(pair))
            assertEquals(mapOf("old.md" to "bye"), a.local.recycled())
            assertTrue(drive.children(pair).any { it.name == "old.md" && it.trashed })
            // The move was detected: Drive moved the same file instead of re-uploading it.
            assertEquals("keep.md", drive.node(keepId).name)
            assertTrue(!drive.node(keepId).trashed && pair !in drive.node(keepId).parents)
        }

    @Test
    fun `simultaneous edits keep both versions`() =
        runTest {
            val pair = newPair()
            val a = Phone(pair, "A")
            val b = Phone(pair, "B")
            a.local.write("doc.md", "base")
            a.syncClean()
            b.syncClean()
            a.local.write("doc.md", "from A")
            b.local.write("doc.md", "from B")
            a.syncClean()
            b.syncClean()
            a.syncClean()
            val expected = setOf("from A", "from B")
            assertEquals(expected, drive.tree(pair).values.toSet())
            assertEquals(
                expected,
                a.local
                    .files()
                    .values
                    .toSet(),
            )
            assertEquals(a.local.files(), b.local.files())
        }

    @Test
    fun `a trashed pair folder pauses instead of deleting the phone's files`() =
        runTest {
            val pair = newPair()
            val phone = Phone(pair, "A")
            phone.local.write("a.md", "keep me")
            phone.syncClean()
            drive.trash(pair)
            val outcome = assertInstanceOf(SyncOutcome.Paused::class.java, phone.sync())
            assertInstanceOf(PauseReason.RemoteUnreachable::class.java, outcome.reason)
            assertEquals(mapOf("a.md" to "keep me"), phone.local.files())
        }
}
