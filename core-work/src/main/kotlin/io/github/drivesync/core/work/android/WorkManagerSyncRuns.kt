package io.github.drivesync.core.work.android

import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.work.SyncRuns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** [SyncRuns] over WorkManager: cancelling is asynchronous, so [stopAll] waits until no sync is running. */
class WorkManagerSyncRuns
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val scheduler: SyncScheduler,
    ) : SyncRuns {
        override suspend fun stopAll() {
            scheduler.cancelAll()
            withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
                workManager
                    .getWorkInfosByTagFlow(SyncScheduler.TAG)
                    .first { infos -> infos.none { it.state == WorkInfo.State.RUNNING } }
            }
        }

        /** The media trigger is re-armed by [SyncStartup], which reacts to the settings change. */
        override fun restart(settings: AppSettings) {
            scheduler.schedulePeriodic(settings)
            scheduler.syncNow()
        }

        private companion object {
            /** A cancelled run stops at its next suspension point; a transfer chunk takes seconds at most. */
            const val STOP_TIMEOUT_MILLIS = 30_000L
        }
    }
