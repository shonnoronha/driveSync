package io.github.drivesync.core.work

import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.sync.store.AccessMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModeSwitchTest {
    /** Records the order of run control calls and what the base and mode were at each one. */
    private class RecordingRuns(
        private val h: WorkHarness,
    ) : SyncRuns {
        val events = mutableListOf<String>()

        override suspend fun stopAll() {
            events += "stop(mode=${h.settings.current().accessMode})"
        }

        override fun restart(settings: AppSettings) {
            events += "restart(mode=${settings.accessMode})"
        }
    }

    private fun test(block: suspend TestScope.(WorkHarness, RecordingRuns, AccessModeSwitcher) -> Unit) =
        runTest {
            val h = WorkHarness(backgroundScope)
            val runs = RecordingRuns(h)
            try {
                block(h, runs, AccessModeSwitcher(h.settings, h.pairs, runs))
            } finally {
                h.close()
            }
        }

    @Test
    fun `turning on saves the mode and syncs`() =
        test { h, runs, switcher ->
            switcher.enable()

            assertEquals(AccessMode.FULL_DRIVE, h.settings.current().accessMode)
            assertEquals(listOf("restart(mode=FULL_DRIVE)"), runs.events)
        }

    @Test
    fun `turning off stops runs before saving`() =
        test { h, runs, switcher ->
            h.settings.setAccessMode(AccessMode.FULL_DRIVE)

            switcher.disable()

            assertEquals(AccessMode.APP_FOLDER, h.settings.current().accessMode)
            assertEquals(listOf("stop(mode=FULL_DRIVE)", "restart(mode=APP_FOLDER)"), runs.events)
        }

    @Test
    fun `turning off twice does nothing more`() =
        test { _, runs, switcher ->
            switcher.disable()
            assertEquals(emptyList<String>(), runs.events)
        }

    @Test
    fun `turning off keeps website files on the phone`() =
        test { h, _, switcher ->
            h.signIn()
            h.settings.setAccessMode(AccessMode.FULL_DRIVE)
            val id = h.addPair("Notes")
            val phone = h.locals.getValue(id)
            val drive = h.drives.getValue(id)
            phone.write("mine.md", "m")
            drive.write("from-web.pdf", "w")
            assertFalse("no retry needed", h.coordinator.syncPair(id, false))
            assertEquals("w", phone.content("from-web.pdf"))

            switcher.disable()
            drive.hideFromApp("from-web.pdf") // drive.file can't see it any more
            assertNull(h.pairs.baseStore(id).load())
            assertFalse("no retry needed", h.coordinator.syncPair(id, false))

            assertEquals(mapOf("mine.md" to "m", "from-web.pdf" to "w"), phone.files())
            assertEquals(emptyMap<String, String>(), phone.recycled())
            assertEquals(emptyList<String>(), drive.trashed())
        }
}
