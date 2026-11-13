package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.fakes.md5Hex
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SyncAction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

/** PRD 3.9 rules 4 (re-check before write), 5 (file changed during upload), 6 (temp → verify → rename). */
class TransferSafetyScenarioTest {
    private suspend fun syncedPhone(vararg files: Pair<String, String>): Phone {
        val p = Phone()
        files.forEach { (path, content) -> p.local.write(path, content) }
        p.local.write("anchor.txt", "anchor")
        p.runClean()
        return p
    }

    @Nested
    inner class RecheckBeforeWrite {
        @Test
        fun `Drive edit between scan and upload re-plans and the next run keeps both`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.local.write("a.txt", "local edit")
                p.onRemote = once(RemoteOp.STAT, "a.txt") { p.remote.write("a.txt", "drive edit") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.Upload>("a.txt"))
                assertEquals("drive edit", p.remote.content("a.txt"))
                assertEquals("local edit", p.local.content("a.txt"))
                assertEquals(md5Hex("v1"), p.base.entry("a.txt")?.md5, "base untouched")

                p.restart()
                val second = p.runClean()
                val conflict = second.plan.conflicts.single()
                assertEquals(ConflictKind.BOTH_MODIFIED, conflict.kind)
                assertEquals(ConflictResolution.KEPT_BOTH, conflict.resolution)
                assertEquals("local edit", p.local.content("a.txt"))
                assertEquals("drive edit", p.local.content(copyOf("a.txt")))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local edit between scan and download re-plans and the next run keeps both`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.write("a.txt", "drive edit")
                p.onLocal = once(LocalOp.WRITE_TEMP, ".a.txt.drivesync-part") { p.local.write("a.txt", "local edit") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.Download>("a.txt"))
                assertEquals("local edit", p.local.content("a.txt"))
                p.assertNoTempFiles()

                p.restart()
                p.runClean()
                assertEquals("local edit", p.local.content("a.txt"))
                assertEquals("drive edit", p.local.content(copyOf("a.txt")))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `Drive edit before a trash re-plans and the edit is restored locally`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.local.delete("a.txt")
                p.onRemote = once(RemoteOp.STAT, "a.txt") { p.remote.write("a.txt", "drive edit") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.TrashRemote>("a.txt"))
                assertEquals(emptyList<String>(), p.remote.trashed())

                p.restart()
                p.runClean()
                assertEquals("drive edit", p.local.content("a.txt"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local edit before a recycle re-plans and the edit is uploaded again`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.trashAt("a.txt")
                p.onLocal = once(LocalOp.STAT, "a.txt") { p.local.write("a.txt", "local edit") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.RecycleLocal>("a.txt"))
                assertEquals(emptyMap<String, String>(), p.local.recycled())

                p.restart()
                p.runClean()
                assertEquals("local edit", p.remote.content("a.txt"))
                p.assertMirrored()
                p.assertConverged()
            }
    }

    @Nested
    inner class ChangedDuringUpload {
        @Test
        fun `new file changed while uploading is abandoned and uploaded next run`() =
            runTest {
                val p = syncedPhone()
                p.local.write("big.bin", "v1")
                p.onLocal = once(LocalOp.READ, "big.bin") { p.local.write("big.bin", "v2 longer") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.Upload>("big.bin"))
                assertFalse("big.bin" in p.remote.files())
                assertNull(p.base.entry("big.bin"))

                p.restart()
                p.runClean()
                assertEquals("v2 longer", p.remote.content("big.bin"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `edited file changed while uploading leaves Drive and base at the old version`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                val id = p.remote.fileId("a.txt")
                p.local.write("a.txt", "v2")
                p.onLocal = once(LocalOp.READ, "a.txt") { p.local.write("a.txt", "v3") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.Upload>("a.txt"))
                assertEquals("v1", p.remote.content("a.txt"))
                assertEquals(md5Hex("v1"), p.base.entry("a.txt")?.md5)

                p.restart()
                p.runClean()
                assertEquals("v3", p.remote.content("a.txt"))
                assertEquals(id, p.remote.fileId("a.txt"), "the Drive file is updated in place")
                p.assertMirrored()
                p.assertConverged()
            }
    }

    @Nested
    inner class Downloads {
        @Test
        fun `truncated download fails, removes the temp file and leaves the target untouched`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.write("a.txt", "drive edit")
                p.remote.write("new.txt", "brand new")
                p.remote.downloadTransform = { it.copyOf(it.size - 2) }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Download>("a.txt"))
                assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Download>("new.txt"))
                assertEquals("v1", p.local.content("a.txt"))
                assertFalse(p.local.exists("new.txt"))
                assertEquals(md5Hex("v1"), p.base.entry("a.txt")?.md5)
                p.assertNoTempFiles()

                p.remote.downloadTransform = { it }
                p.runClean()
                assertEquals("drive edit", p.local.content("a.txt"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `same-size corrupted download fails the MD5 check`() =
            runTest {
                val p = syncedPhone("a.txt" to "v1")
                p.remote.write("a.txt", "abcdef")
                p.remote.downloadTransform = { it.reversedArray() }

                val first = p.run()

                val failed = first.outcomeOf<SyncAction.Download>("a.txt")
                assertEquals(ActionOutcome.Failed("downloaded file failed MD5 check"), failed)
                assertEquals("v1", p.local.content("a.txt"))
                p.assertNoTempFiles()

                p.remote.downloadTransform = { it }
                p.runClean()
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `local file appearing at the target during a download re-plans into keep-both`() =
            runTest {
                val p = syncedPhone()
                p.remote.write("new.txt", "drive")
                p.onLocal = once(LocalOp.WRITE_TEMP, ".new.txt.drivesync-part") { p.local.write("new.txt", "local") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Replan::class.java, first.outcomeOf<SyncAction.Download>("new.txt"))
                assertEquals("local", p.local.content("new.txt"))
                p.assertNoTempFiles()

                p.restart()
                p.runClean()
                assertEquals("local", p.local.content("new.txt"))
                assertEquals("drive", p.local.content(copyOf("new.txt")))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `partial temp file of a failed download is deleted by the next run`() =
            runTest {
                val p = syncedPhone()
                p.remote.write("new.txt", "drive")
                p.onRemote = once(RemoteOp.DOWNLOAD, "new.txt") { throw IOException("connection reset") }

                val first = p.run()

                assertInstanceOf(ActionOutcome.Failed::class.java, first.outcomeOf<SyncAction.Download>("new.txt"))
                assertFalse(p.local.exists("new.txt"))

                p.restart()
                p.remote.trashAt("new.txt")
                p.runClean()
                p.assertNoTempFiles()
                p.assertConverged()
            }

        @Test
        fun `a failed download's partial temp file is replaced by the next successful download`() =
            runTest {
                val p = syncedPhone()
                p.remote.write("new.txt", "drive")
                p.onRemote = once(RemoteOp.DOWNLOAD, "new.txt") { throw IOException("connection reset") }
                p.run()

                p.restart()
                p.runClean()
                assertEquals("drive", p.local.content("new.txt"))
                p.assertMirrored()
                p.assertConverged()
            }

        @Test
        fun `next run deletes a leftover temp file whose download is no longer needed`() =
            runTest {
                val p = syncedPhone()
                p.remote.write("new.txt", "drive")
                p.onLocal = once(LocalOp.COMMIT_TEMP, "new.txt") { killed() }
                p.crash()
                assertEquals(setOf(".new.txt.drivesync-part"), p.local.tempFiles(), "precondition: a leftover")
                p.restart()
                p.remote.trashAt("new.txt")

                p.runClean()

                p.assertNoTempFiles()
            }
    }
}
