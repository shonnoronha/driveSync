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
         * Runs now (PRD 3.4 D): one pair, or all of them. The user asked, so only a connection is required; Wi-Fi-only
         * and charging-only rules apply to automatic runs.
         */
        fun syncNow(
            pairId: String? = null,
            deletionsConfirmed: Boolean = false,
        ) {
            val data =
                if (pairId == null) {
                    workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_ALL)
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
