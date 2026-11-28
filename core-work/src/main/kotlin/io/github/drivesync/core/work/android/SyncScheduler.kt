package io.github.drivesync.core.work.android

import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.drivesync.core.data.settings.AppSettings
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All WorkManager scheduling (PRD 3.4, 6.1): one global periodic coordinator, one-time "sync now" runs and the
 * MediaStore content-URI trigger. Unique names keep each kind from piling up.
 */
@Singleton
class SyncScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) {
        /** (Re)schedules the periodic run with the user's interval and network/battery rules. */
        fun schedulePeriodic(settings: AppSettings) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(settings.syncIntervalMinutes.toLong()))
                    .setConstraints(automaticConstraints(settings))
                    .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_ALL))
                    .addTag(TAG)
                    .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Runs now (PRD 3.4 D): one pair, or all of them. Only a connection is required; the app-wide Wi-Fi-only and
         * charging-only rules apply to periodic runs. A one-pair run is always the user's request, so it also ignores
         * that folder's own rules; an all-pairs run ignores them only when [manual] (the user tapped "sync all"),
         * not when the app opens.
         */
        fun syncNow(
            pairId: String? = null,
            deletionsConfirmed: Boolean = false,
            manual: Boolean = false,
        ) {
            val data =
                if (pairId == null) {
                    workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_ALL, SyncWorker.KEY_MANUAL to manual)
                } else {
                    workDataOf(
                        SyncWorker.KEY_MODE to SyncWorker.MODE_PAIR,
                        SyncWorker.KEY_PAIR_ID to pairId,
                        SyncWorker.KEY_CONFIRM_DELETES to deletionsConfirmed,
                    )
                }
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(data)
                    .addTag(TAG)
                    .apply { if (pairId != null) addTag(PAIR_TAG_PREFIX + pairId) }
                    .build()
            // A confirmation must not be dropped behind an ordinary queued run.
            val policy = if (deletionsConfirmed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(NOW_PREFIX + (pairId ?: "all"), policy, request)
        }

        /**
         * Rewind (one pair) to the history point [point]. Runs on any connection, like "sync now"; a second request
         * while one runs is dropped, so the screen follows the one in progress by [rewindWorkName].
         */
        fun rewind(
            pairId: String,
            point: Long,
        ) {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(
                        workDataOf(
                            SyncWorker.KEY_MODE to SyncWorker.MODE_REWIND,
                            SyncWorker.KEY_PAIR_ID to pairId,
                            SyncWorker.KEY_REWIND_POINT to point,
                        ),
                    ).addTag(TAG)
                    .addTag(PAIR_TAG_PREFIX + pairId)
                    .build()
            workManager.enqueueUniqueWork(rewindWorkName(pairId), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Wakes the app about a minute after MediaStore changes (new photo or video), with no running service. The
         * trigger fires once, so the worker calls this again after each run.
         */
        fun watchMedia(settings: AppSettings) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(settings.chargingOnly)
                    .setRequiresStorageNotLow(true)
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(MEDIA_SETTLE)
                    .setTriggerContentMaxDelay(MEDIA_MAX_DELAY)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_MEDIA))
                    .addTag(TAG)
                    .build()
            workManager.enqueueUniqueWork(MEDIA, ExistingWorkPolicy.REPLACE, request)
        }

        fun stopWatchingMedia() {
            workManager.cancelUniqueWork(MEDIA)
        }

        /** Sign-out (FR-A5): nothing may run afterwards. */
        fun cancelAll() {
            workManager.cancelAllWorkByTag(TAG)
        }

        companion object {
            const val TAG = "drivesync"
            const val PERIODIC = "sync-periodic"
            const val MEDIA = "sync-media"
            const val NOW_PREFIX = "sync-now-"
            private const val PAIR_TAG_PREFIX = "pair:"
            private const val REWIND_PREFIX = "rewind-"

            fun rewindWorkName(pairId: String): String = REWIND_PREFIX + pairId

            /** The pair a one-pair run belongs to, from its WorkInfo tags; null for all-pairs runs. */
            fun pairIdOf(tags: Set<String>): String? =
                tags.firstOrNull { it.startsWith(PAIR_TAG_PREFIX) }?.removePrefix(PAIR_TAG_PREFIX)

            /** Waiting for MediaStore to settle doubles as the stability check for camera files (PRD 3.6). */
            val MEDIA_SETTLE: Duration = Duration.ofSeconds(30)
            val MEDIA_MAX_DELAY: Duration = Duration.ofMinutes(2)

            internal fun automaticConstraints(settings: AppSettings): Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(settings.chargingOnly)
                    .setRequiresStorageNotLow(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
        }
    }
