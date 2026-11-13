package io.github.drivesync.core.work.android

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.work.MediaFolders
import io.github.drivesync.core.work.SyncCoordinator

/**
 * The single sync worker. [MODE_ALL] is the periodic coordinator and "sync all now", [MODE_PAIR] one pair, and
 * [MODE_MEDIA] the MediaStore trigger, which syncs the media pairs and re-arms itself. Work is checkpointed per
 * file, so being stopped by the system (10-minute limit, Doze) loses nothing; the next run continues.
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val coordinator: SyncCoordinator,
        private val scheduler: SyncScheduler,
        private val settings: SettingsRepository,
        private val notifier: AndroidSyncNotifier,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            promoteToForeground()
            val retry =
                when (inputData.getString(KEY_MODE)) {
                    MODE_PAIR -> {
                        val pairId = inputData.getString(KEY_PAIR_ID) ?: return Result.failure()
                        coordinator.syncPair(pairId, inputData.getBoolean(KEY_CONFIRM_DELETES, false))
                    }

                    MODE_MEDIA -> {
                        coordinator.syncAll { MediaFolders.isMediaTree(it.localTreeUri) }.also {
                            scheduler.watchMedia(settings.current())
                        }
                    }

                    else -> {
                        coordinator.syncAll()
                    }
                }
            return if (retry && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

        /** Best effort: Android 12+ refuses foreground starts from the background, and the sync runs anyway. */
        private suspend fun promoteToForeground() {
            try {
                setForeground(foregroundInfo())
            } catch (_: IllegalStateException) {
                // Not allowed right now (ForegroundServiceStartNotAllowedException is an IllegalStateException).
            }
        }

        private fun foregroundInfo(): ForegroundInfo {
            val notification = notifier.progress()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    AndroidSyncNotifier.ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(AndroidSyncNotifier.ID_PROGRESS, notification)
            }
        }

        companion object {
            const val KEY_MODE = "mode"
            const val KEY_PAIR_ID = "pairId"
            const val KEY_CONFIRM_DELETES = "confirmDeletes"
            const val MODE_ALL = "all"
            const val MODE_PAIR = "pair"
            const val MODE_MEDIA = "media"
            private const val MAX_RETRIES = 5
        }
    }
