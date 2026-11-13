package io.github.drivesync.core.work

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.drive.api.DriveException
import io.github.drivesync.core.drive.auth.AuthRequiredException
import io.github.drivesync.core.sync.fakes.LocalOp
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SyncCoordinatorTest {
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
    fun `nothing runs before sign-in`() =
        test { h ->
            val id = h.addPair("Notes")
            h.locals.getValue(id).write("a.md", "a")
            assertFalse(h.coordinator.syncAll())
            assertFalse(h.coordinator.syncPair(id, false))
            assertEquals(null, h.drives.getValue(id).content("a.md"))
        }

    @Test
    fun `syncs every enabled pair`() =
        test { h ->
            h.signIn()
            val a = h.addPair("A")
            val b = h.addPair("B")
            val off = h.addPair("Off")
            h.pairs.setEnabled(off, false)
            listOf(a, b, off).forEach { h.locals.getValue(it).write("x.md", it) }
            assertFalse(h.coordinator.syncAll())
            assertEquals(a, h.drives.getValue(a).content("x.md"))
            assertEquals(b, h.drives.getValue(b).content("x.md"))
            assertEquals(null, h.drives.getValue(off).content("x.md"))
            assertEquals(PairStatus.IDLE, h.pairs.pair(a)!!.status)
        }

    @Test
    fun `a filter limits the run to some pairs`() =
        test { h ->
            h.signIn()
            val camera = h.addPair("DCIM")
            val notes = h.addPair("Notes")
            h.locals.getValue(camera).write("p.jpg", "p")
            h.locals.getValue(notes).write("n.md", "n")
            h.coordinator.syncAll { MediaFolders.isMediaTree(it.localTreeUri) }
            assertEquals("p", h.drives.getValue(camera).content("p.jpg"))
            assertEquals(null, h.drives.getValue(notes).content("n.md"))
        }

    @Test
    fun `a pause notifies once`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.locals.getValue(id).unavailableReason = "SD card removed"
            h.coordinator.syncAll()
            h.coordinator.syncAll()
            assertEquals(listOf("attention:Notes"), h.notifier.events)
            assertEquals(PairStatus.NEEDS_ATTENTION, h.pairs.pair(id)!!.status)
        }

    @Test
    fun `conflicts and failures are notified`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.locals.getValue(id).write("c.md", "phone")
            h.drives.getValue(id).write("c.md", "drive")
            h.locals.getValue(id).write("up.md", "up")
            h.locals.getValue(id).beforeOp =
                { op, p -> if (op == LocalOp.READ && p?.value == "up.md") throw IOException("disk") }
            h.coordinator.syncAll()
            assertEquals(listOf("failures:Notes:1", "conflicts:1"), h.notifier.events)
        }

    @Test
    fun `the device name goes into conflict copies`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.locals.getValue(id).write("c.md", "phone")
            h.drives.getValue(id).write("c.md", "drive")
            h.settings.setDeviceName("Tablet")
            h.coordinator.syncAll()
            assertTrue(
                h.locals
                    .getValue(id)
                    .files()
                    .keys
                    .toString(),
                h.locals.getValue(id).files().keys.any {
                    "Tablet" in
                        it
                },
            )
        }

    @Test
    fun `revoked sign-in pauses the pair`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.remotes.getValue(id).error = AuthRequiredException("revoked")
            assertFalse(h.coordinator.syncAll())
            assertEquals(PairStatus.NEEDS_ATTENTION, h.pairs.pair(id)!!.status)
            assertEquals(listOf("attention:Notes"), h.notifier.events)
        }

    @Test
    fun `a full Drive pauses the pair`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.remotes.getValue(id).error = DriveException.QuotaExceeded("full")
            h.coordinator.syncAll()
            assertTrue(
                h.pairs
                    .pair(id)!!
                    .statusDetail!!
                    .contains("full"),
            )
        }

    @Test
    fun `network errors ask for a retry`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.remotes.getValue(id).error = IOException("offline")
            assertTrue(h.coordinator.syncAll())
            assertEquals(PairStatus.IDLE, h.pairs.pair(id)!!.status)
            assertEquals(emptyList<String>(), h.notifier.events)
            h.remotes.getValue(id).error = IOException()
            assertTrue(h.coordinator.syncPair(id, false))
        }

    @Test
    fun `a mass delete waits for the user`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            val local = h.locals.getValue(id)
            repeat(30) { local.write("f$it.md", "$it") }
            h.coordinator.syncAll()
            repeat(25) { local.delete("f$it.md") }
            h.coordinator.syncAll()
            assertEquals(PairStatus.WAITING_FOR_CONFIRMATION, h.pairs.pair(id)!!.status)
            assertEquals(
                30,
                h.drives
                    .getValue(id)
                    .files()
                    .size,
            )
            h.coordinator.syncPair(id, deletionsConfirmed = true)
            assertEquals(
                5,
                h.drives
                    .getValue(id)
                    .files()
                    .size,
            )
            assertEquals(PairStatus.IDLE, h.pairs.pair(id)!!.status)
        }

    @Test
    fun `unknown and disabled pairs are skipped`() =
        test { h ->
            h.signIn()
            assertFalse(h.coordinator.syncPair("missing", false))
            val id = h.addPair("Off")
            h.pairs.setEnabled(id, false)
            assertEquals(RunResult.Skipped, h.runner.run(id, null))
        }

    @Test
    fun `the log is pruned by retention`() =
        test { h ->
            h.signIn()
            val id = h.addPair("Notes")
            h.remotes.getValue(id).error = IOException("old failure") // Logged at time 0.
            h.coordinator.syncAll()
            h.remotes.getValue(id).error = null
            h.coordinator.syncAll()
            assertEquals(0, h.db.activity().deleteOlderThan(Long.MAX_VALUE))
        }
}
