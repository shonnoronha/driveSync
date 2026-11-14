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
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.engine.SyncProgress
import io.github.drivesync.core.work.MediaFolders
import io.github.drivesync.core.work.PairProgressListener
import io.github.drivesync.core.work.SyncCoordinator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

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
        override suspend fun doWork(): Result =
            coroutineScope {
                val foreground = promoteToForeground(null, null)
                // Progress is published at most every PROGRESS_INTERVAL_MS: each update is a database write.
                val latest = MutableStateFlow<Pair<SyncPair, SyncProgress>?>(null)
                val publisher =
                    launch {
                        latest.filterNotNull().collect { (pair, progress) ->
                            setProgress(WorkProgress.data(pair.id, progress))
                            if (foreground) promoteToForeground(pair.name, progress)
                            delay(PROGRESS_INTERVAL_MS)
                        }
                    }
                val onProgress = PairProgressListener { pair, progress -> latest.value = pair to progress }
                val retry =
                    when (inputData.getString(KEY_MODE)) {
                        MODE_PAIR -> {
                            val pairId = inputData.getString(KEY_PAIR_ID)
                            if (pairId == null) {
                                publisher.cancel()
                                return@coroutineScope Result.failure()
                            }
                            coordinator.syncPair(pairId, inputData.getBoolean(KEY_CONFIRM_DELETES, false), onProgress)
                        }

                        MODE_MEDIA -> {
                            coordinator.syncAll({ MediaFolders.isMediaTree(it.localTreeUri) }, onProgress).also {
                                scheduler.watchMedia(settings.current())
                            }
                        }

                        else -> {
                            coordinator.syncAll(onProgress = onProgress)
                        }
                    }
                publisher.cancel()
                if (retry && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }

        override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, null)

        /**
         * Best effort: Android 12+ refuses foreground starts from the background, and the sync runs anyway.
         * Returns whether the worker is in the foreground (so later progress updates its notification).
         */
        private suspend fun promoteToForeground(
            pairName: String?,
            progress: SyncProgress?,
        ): Boolean =
            try {
                setForeground(foregroundInfo(pairName, progress))
                true
            } catch (_: IllegalStateException) {
                // Not allowed right now (ForegroundServiceStartNotAllowedException is an IllegalStateException).
                false
            }

        private fun foregroundInfo(
            pairName: String?,
            progress: SyncProgress?,
        ): ForegroundInfo {
            val notification = notifier.progress(pairName, progress)
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
            private const val PROGRESS_INTERVAL_MS = 300L
        }
    }
