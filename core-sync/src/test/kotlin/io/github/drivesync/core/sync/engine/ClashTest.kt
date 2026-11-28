package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.plan.ConflictKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Drive name clashes (FR-S13): Drive allows several files with one name. Drive is never renamed for this; the
 * extra file lives on the phone under a conflict name and gets the Drive name back once the clash is gone.
 */
class ClashTest {
    private val copy = copyOf("a.txt")

    /** A synced a.txt, then a second a.txt added on Drive (another app or the website). Returns its id. */
    private suspend fun clashedPhone(): Pair<Phone, String> {
        val p = Phone()
        p.local.write("a.txt", "A")
        p.runClean()
        val twin = p.remote.writeDuplicate("a.txt", "W")
        val outcome = p.runClean()
        assertEquals(
            ConflictKind.DUPLICATE_REMOTE_NAME,
            outcome.plan.conflicts
                .single()
                .kind,
        )
        return p to twin
    }

    private fun Phone.assertNoDriveRename() =
        assertFalse(remoteOps.any { it.first == RemoteOp.MOVE }, "Drive must not be renamed")

    @Test
    fun `Drive duplicates stay untouched`() =
        runTest {
            val (p, _) = clashedPhone()

            p.assertNoDriveRename()
            assertEquals(mapOf("a.txt" to listOf("A", "W")), p.remote.files())
            assertEquals(mapOf("a.txt" to "A", copy to "W"), p.local.files())
            assertEquals(
                "a.txt",
                p.base
                    .entry(copy)
                    ?.remotePath
                    ?.value,
            )
            p.assertConverged()
        }

    @Test
    fun `editing the phone copy updates its twin`() =
        runTest {
            val (p, _) = clashedPhone()
            p.local.write(copy, "W2")

            p.runClean()

            p.assertNoDriveRename()
            assertEquals(mapOf("a.txt" to listOf("A", "W2")), p.remote.files())
            p.assertConverged()
        }

    @Test
    fun `a Drive edit of the twin reaches the copy`() =
        runTest {
            val (p, twin) = clashedPhone()
            p.remote.writeById(twin, "W2")

            p.runClean()

            assertEquals(mapOf("a.txt" to "A", copy to "W2"), p.local.files())
            p.assertConverged()
        }

    @Test
    fun `removing the other file gives the name back`() =
        runTest {
            val (p, _) = clashedPhone()
            p.remote.trashById(checkNotNull(p.base.entry("a.txt")?.remoteFileId))

            p.runClean() // The phone's a.txt follows its Drive file to the recycle folder.
            p.runClean() // Now the name is free.

            assertEquals(mapOf("a.txt" to "W"), p.local.files())
            assertEquals(mapOf("a.txt" to "A"), p.local.recycled())
            assertEquals(null, p.base.entry("a.txt")?.remotePath)
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `renaming the twin on Drive renames the copy`() =
        runTest {
            val (p, twin) = clashedPhone()
            p.remote.renameById(twin, "w.txt")

            p.runClean()

            assertEquals(mapOf("a.txt" to "A", "w.txt" to "W"), p.local.files())
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `deleting the copy trashes its twin`() =
        runTest {
            val (p, _) = clashedPhone()
            p.local.delete(copy)

            p.runClean()

            assertEquals(listOf("a.txt"), p.remote.trashed())
            assertEquals(mapOf("a.txt" to listOf("A")), p.remote.files())
            p.assertMirrored()
            p.assertConverged()
        }

    @Test
    fun `three files with one name`() =
        runTest {
            val p = Phone()
            p.remote.write("a.txt", "1")
            p.remote.writeDuplicate("a.txt", "2")
            p.remote.writeDuplicate("a.txt", "3")

            val outcome = p.runClean()

            assertEquals(2, outcome.plan.conflicts.size)
            assertEquals(
                setOf("1", "2", "3"),
                p.local
                    .files()
                    .values
                    .toSet(),
            )
            assertEquals(3, p.local.files().size)
            p.assertNoDriveRename()
            p.assertConverged()
        }
}
