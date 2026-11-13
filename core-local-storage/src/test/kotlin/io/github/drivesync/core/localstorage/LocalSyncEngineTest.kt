package io.github.drivesync.core.localstorage

import io.github.drivesync.core.sync.engine.ActionOutcome
import io.github.drivesync.core.sync.engine.SyncEngine
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.fakes.InMemoryBaseStore
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.PauseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

/** The core-sync engine with a real (filesystem) phone folder: what step 4 must not break. */
class LocalSyncEngineTest {
    @TempDir
    lateinit var dir: File

    private val tree by lazy { FileDocumentTree(dir) }
    private val drive = FakeRemoteStore()
    private val engine by lazy {
        SyncEngine(DocumentTreeLocalStore(tree, Dispatchers.Unconfined), drive, InMemoryBaseStore())
    }

    private suspend fun sync(): SyncOutcome =
        engine.sync(PairSettings(), ConflictStamp(LocalDateTime.of(2026, 11, 13, 9, 30), "Pixel"))

    private suspend fun syncClean() {
        val first = assertInstanceOf(SyncOutcome.Completed::class.java, sync())
        assertEquals(emptyList<Any>(), first.report.results.filter { it.outcome != ActionOutcome.Done })
        val second = assertInstanceOf(SyncOutcome.Completed::class.java, sync())
        assertEquals(emptyList<Any>(), second.plan.actions, "not converged")
    }

    @Test
    fun `two-way sync with downloads, uploads, moves, deletes and a conflict`() =
        runTest {
            tree.write("phone.md", "from phone")
            drive.write("drive/one.md", "from drive")
            syncClean()
            assertEquals(mapOf("drive/one.md" to "from drive", "phone.md" to "from phone"), tree.files())
            assertEquals("from phone", drive.content("phone.md"))

            tree.userMove("phone.md", "moved/phone.md")
            drive.write("drive/one.md", "drive edit")
            syncClean()
            assertEquals("from phone", drive.content("moved/phone.md"))
            assertEquals("drive edit", tree.read("drive/one.md"))

            tree.write("drive/one.md", "phone edit")
            drive.write("drive/one.md", "other edit")
            syncClean()
            val notes =
                tree
                    .files()
                    .filterKeys { it.startsWith("drive/") }
                    .values
                    .toSet()
            assertEquals(setOf("phone edit", "other edit"), notes)

            File(dir, "moved/phone.md").delete()
            syncClean()
            assertEquals(null, drive.content("moved/phone.md"))
        }

    @Test
    fun `a revoked grant pauses the pair and Drive keeps everything`() =
        runTest {
            tree.write("a.md", "a")
            syncClean()
            tree.unavailable = true
            val paused = assertInstanceOf(SyncOutcome.Paused::class.java, sync())
            assertInstanceOf(PauseReason.LocalUnreachable::class.java, paused.reason)
            assertEquals("a", drive.content("a.md"))
        }
}
