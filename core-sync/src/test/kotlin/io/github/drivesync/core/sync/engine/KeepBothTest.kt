package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Keep-both conflicts in two-way pairs leave Drive alone: the Drive file keeps its id, name and content, and the
 * phone's version becomes the conflict copy on both sides.
 */
class KeepBothTest {
    private val copy = copyOf("doc.txt")

    private suspend fun conflictedPhone(): Phone {
        val p = Phone()
        p.local.write("doc.txt", "v1")
        p.runClean()
        p.local.write("doc.txt", "phone edit")
        p.remote.write("doc.txt", "drive edit")
        return p
    }

    private fun Phone.assertNothingDeleted() {
        assertEquals(emptyList<String>(), remote.trashed(), "Drive trash")
        assertEquals(emptyMap<String, String>(), local.recycled(), "phone recycle folder")
    }

    @Test
    fun `Drive file keeps its id and name`() =
        runTest {
            val p = conflictedPhone()
            val driveId = p.remote.fileId("doc.txt")

            val outcome = p.runClean()

            assertEquals(
                ConflictKind.BOTH_MODIFIED,
                outcome.plan.conflicts
                    .single()
                    .kind,
            )
            assertEquals(driveId, p.remote.fileId("doc.txt"))
            assertEquals("drive edit", p.remote.content("doc.txt"))
            assertEquals("phone edit", p.remote.content(copy))
            assertEquals(mapOf("doc.txt" to "drive edit", copy to "phone edit"), p.local.files())
            assertFalse(p.remoteOps.any { it.first == RemoteOp.MOVE }, "no rename on Drive")
            p.assertNothingDeleted()
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `new on both sides keeps the Drive name`() =
        runTest {
            val p = Phone()
            p.local.write("doc.txt", "phone")
            val driveId = p.remote.write("doc.txt", "drive")

            val outcome = p.runClean()

            assertEquals(
                ConflictKind.BOTH_NEW,
                outcome.plan.conflicts
                    .single()
                    .kind,
            )
            assertEquals(driveId, p.remote.fileId("doc.txt"))
            assertEquals(mapOf("doc.txt" to "drive", copy to "phone"), p.local.files())
            p.assertNothingDeleted()
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `failed download after the rename recovers`() =
        runTest {
            val p = conflictedPhone()
            p.onLocal = { op, path ->
                if (op == LocalOp.WRITE_TEMP && path?.value == ".doc.txt.drivesync-part") throw IOException("disk full")
            }

            val first = p.run()

            assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Download>("doc.txt"))
            assertEquals("phone edit", p.local.content(copy))
            assertFalse(p.local.exists("doc.txt"))

            p.restart()
            val second = p.runClean()

            // The original name had no base left, so this is a plain download, not "deleted on the phone".
            assertEquals(emptyList<Any>(), second.plan.conflicts)
            assertEquals(mapOf("doc.txt" to "drive edit", copy to "phone edit"), p.local.files())
            p.assertNothingDeleted()
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `failed upload of the copy recovers`() =
        runTest {
            val p = conflictedPhone()
            p.onRemote = { op, path -> if (op == RemoteOp.UPLOAD && path?.value == copy) throw IOException("offline") }

            val first = p.run()

            assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Upload>(copy))
            assertEquals(mapOf("doc.txt" to "drive edit", copy to "phone edit"), p.local.files())
            assertEquals(null, p.remote.content(copy))

            p.restart()
            p.runClean()

            assertEquals("phone edit", p.remote.content(copy))
            p.assertNothingDeleted()
            p.assertMirrored()
            p.assertConverged()
        }
}
