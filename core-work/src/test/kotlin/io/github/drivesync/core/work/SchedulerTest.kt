package io.github.drivesync.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.work.android.AndroidSyncNotifier
import io.github.drivesync.core.work.android.SyncScheduler
import io.github.drivesync.core.work.android.SyncWorker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(workManager)
    }

    private fun unique(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `periodic run follows the settings`() {
        scheduler.schedulePeriodic(AppSettings(syncIntervalMinutes = 60, wifiOnly = true, chargingOnly = true))
        val info = unique(SyncScheduler.PERIODIC).single()
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresCharging())
        assertEquals(60L * 60 * 1000, info.periodicityInfo!!.repeatIntervalMillis)
        scheduler.schedulePeriodic(AppSettings())
        assertEquals(NetworkType.CONNECTED, unique(SyncScheduler.PERIODIC).single().constraints.requiredNetworkType)
    }

    @Test
    fun `sync now ignores wifi and charging rules`() {
        scheduler.syncNow("p1")
        scheduler.syncNow()
        val info = unique(SyncScheduler.NOW_PREFIX + "p1").single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("p1", SyncScheduler.pairIdOf(info.tags))
        assertEquals(null, SyncScheduler.pairIdOf(unique(SyncScheduler.NOW_PREFIX + "all").single().tags))
        assertEquals(1, unique(SyncScheduler.NOW_PREFIX + "all").size)
    }

    @Test
    fun `a delete confirmation replaces a queued run`() {
        scheduler.syncNow("p1")
        scheduler.syncNow("p1", deletionsConfirmed = true)
        val infos = unique(SyncScheduler.NOW_PREFIX + "p1")
        assertEquals(1, infos.count { !it.state.isFinished })
    }

    @Test
    fun `media trigger watches MediaStore`() {
        scheduler.watchMedia(AppSettings())
        val info = unique(SyncScheduler.MEDIA).single()
        assertEquals(2, info.constraints.contentUriTriggers.size)
        scheduler.stopWatchingMedia()
        assertTrue(unique(SyncScheduler.MEDIA).single().state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun `sign-out cancels everything`() {
        scheduler.schedulePeriodic(AppSettings())
        scheduler.syncNow()
        scheduler.cancelAll()
        assertTrue(workManager.getWorkInfosByTag(SyncScheduler.TAG).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `worker modes`() =
        runTest {
            val h = WorkHarness(backgroundScope)
            try {
                h.signIn()
                val id = h.addPair("DCIM")
                h.locals.getValue(id).write("p.jpg", "p")

                fun worker(vararg data: Pair<String, Any?>): SyncWorker {
                    val factory =
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ) = SyncWorker(
                                appContext,
                                workerParameters,
                                h.coordinator,
                                scheduler,
                                h.settings,
                                AndroidSyncNotifier(appContext),
                            )
                        }
                    return TestListenableWorkerBuilder<SyncWorker>(
                        context,
                        workDataOf(*data),
                    ).setWorkerFactory(factory).build()
                }

                assertEquals(
                    ListenableWorker.Result.failure(),
                    worker(SyncWorker.KEY_MODE to SyncWorker.MODE_PAIR).doWork(),
                )
                assertEquals(
                    ListenableWorker.Result.success(),
                    worker(SyncWorker.KEY_MODE to SyncWorker.MODE_MEDIA).doWork(),
                )
                assertEquals("p", h.drives.getValue(id).content("p.jpg"))
                assertEquals(1, unique(SyncScheduler.MEDIA).size) // Re-armed.

                h.remotes.getValue(id).error = IOException("offline")
                assertEquals(ListenableWorker.Result.retry(), worker().doWork())
                val pairRun = worker(SyncWorker.KEY_MODE to SyncWorker.MODE_PAIR, SyncWorker.KEY_PAIR_ID to id)
                assertEquals(ListenableWorker.Result.retry(), pairRun.doWork())
            } finally {
                h.close()
            }
        }

    @Test
    fun `notifier posts without crashing`() {
        val notifier = AndroidSyncNotifier(context)
        val pair =
            io.github.drivesync.core.data.pairs.SyncPair(
                "p",
                "Notes",
                "t",
                "f",
                io.github.drivesync.core.sync.model
                    .PairSettings(),
                true,
                io.github.drivesync.core.data.db.PairStatus.IDLE,
                null,
                null,
            )
        notifier.needsAttention(pair, "SD card removed")
        notifier.conflicts(2)
        notifier.failures(pair, 1)
        notifier.progress(null, null)
    }
}
