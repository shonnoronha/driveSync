package io.github.drivesync.core.work

import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.activity.SyncRecorder
import io.github.drivesync.core.data.history.HistoryRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.drive.api.DriveException
import io.github.drivesync.core.drive.auth.AuthRequiredException
import io.github.drivesync.core.sync.engine.ActionOutcome
import io.github.drivesync.core.sync.engine.RewindOutcome
import io.github.drivesync.core.sync.engine.SyncEngine
import io.github.drivesync.core.sync.engine.SyncOutcome
import io.github.drivesync.core.sync.engine.SyncProgress
import io.github.drivesync.core.sync.engine.SyncProgressListener
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.RewindRefusal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** What one pair's run means for scheduling and notifications. */
sealed interface RunResult {
    /** Disabled or removed pair. */
    data object Skipped : RunResult

    data class Completed(
        val transfers: Int,
        val newConflicts: Int,
        val failed: Int,
    ) : RunResult

    /** Paused, waiting for delete confirmation, signed out or Drive full: only the user can move it on. */
    data class NeedsUser(
        val detail: String,
    ) : RunResult

    /** A transient failure (network, server): try again later. */
    data class Retry(
        val detail: String,
    ) : RunResult
}

/** What a rewind did (Rewind). */
sealed interface RewindResult {
    data class Done(
        /** Files and folders put back, brought back or sent to the trash. */
        val changed: Int,
        /** Left alone on purpose: changed since the last sync, version gone, name taken. */
        val skipped: Int,
        val failed: Int,
    ) : RewindResult

    data class Refused(
        val reason: RewindRefusal,
    ) : RewindResult

    data class NeedsUser(
        val detail: String,
    ) : RewindResult

    data class Retry(
        val detail: String,
    ) : RewindResult
}

/**
 * Runs one pair end to end: stores → engine → recorder. At most one run per pair at a time (PRD 7), even when the
 * periodic worker and a "sync now" overlap.
 */
@Singleton
class PairSyncRunner
    @Inject
    constructor(
        private val pairs: PairRepository,
        private val recorder: SyncRecorder,
        private val activity: ActivityRepository,
        private val stores: SyncStores,
        private val environment: SyncEnvironment,
        private val history: HistoryRepository,
    ) {
        private val locks = ConcurrentHashMap<String, Mutex>()

        suspend fun run(
            pairId: String,
            deviceName: String?,
            deletionsConfirmed: Boolean = false,
            progress: SyncProgressListener = SyncProgressListener.NONE,
        ): RunResult =
            locks.getOrPut(pairId) { Mutex() }.withLock {
                val pair = pairs.pair(pairId)?.takeIf { it.enabled } ?: return@withLock RunResult.Skipped
                try {
                    val engine = SyncEngine(stores.local(pair), stores.remote(pair), pairs.baseStore(pair.id))
                    val stamp = ConflictStamp(environment.now(), deviceName ?: environment.defaultDeviceName)
                    val outcome =
                        engine.sync(
                            pair.settings,
                            stamp,
                            deletionsConfirmed,
                            activity.resolutions(pair.id),
                            progress,
                        )
                    recorder.record(pair.id, outcome)
                    if (outcome is SyncOutcome.Completed) activity.clearResolutions(pair.id)
                    outcome.toResult(pair.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthRequiredException) {
                    needsUser(pair.id, "${GoogleAccess.SIGN_IN_AGAIN} to keep syncing (${e.message})")
                } catch (e: DriveException.QuotaExceeded) {
                    needsUser(pair.id, "Google Drive is full: ${e.message}")
                } catch (e: IOException) {
                    val detail = e.message ?: e.javaClass.simpleName
                    recorder.recordFailure(pair.id, detail, needsUser = false)
                    RunResult.Retry(detail)
                }
            }

        /**
         * Rewind (one pair): back to how the folder was right after the run at [point] of its history. Same lock as
         * syncs, so a rewind never overlaps a sync of the same pair. Its changes are a new point, so it can be undone.
         */
        suspend fun rewind(
            pairId: String,
            point: Long,
            progress: SyncProgressListener = SyncProgressListener.NONE,
        ): RewindResult =
            locks.getOrPut(pairId) { Mutex() }.withLock {
                val pair = pairs.pair(pairId) ?: return@withLock RewindResult.Refused(RewindRefusal.NO_HISTORY)
                val timeline = history.timeline(pairId)
                if (point !in timeline.points) return@withLock RewindResult.Refused(RewindRefusal.POINT_GONE)
                try {
                    val engine = SyncEngine(stores.local(pair), stores.remote(pair), pairs.baseStore(pair.id))
                    when (val outcome = engine.rewind(timeline.stateAsOf(point), pair.settings, progress)) {
                        is RewindOutcome.Refused -> {
                            RewindResult.Refused(outcome.reason)
                        }

                        is RewindOutcome.Completed -> {
                            recorder.record(pair.id, SyncOutcome.Completed(outcome.plan.asSyncPlan(), outcome.report))
                            val results = outcome.report.results
                            RewindResult.Done(
                                changed =
                                    results.count {
                                        it.outcome == ActionOutcome.Done &&
                                            SyncProgress.counts(it.action)
                                    },
                                skipped = outcome.plan.skipped.size,
                                failed = results.count { it.outcome is ActionOutcome.Failed },
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthRequiredException) {
                    RewindResult.NeedsUser("${GoogleAccess.SIGN_IN_AGAIN} (${e.message})")
                } catch (e: DriveException.QuotaExceeded) {
                    RewindResult.NeedsUser("Google Drive is full: ${e.message}")
                } catch (e: IOException) {
                    RewindResult.Retry(e.message ?: e.javaClass.simpleName)
                }
            }

        private suspend fun needsUser(
            pairId: String,
            detail: String,
        ): RunResult {
            recorder.recordFailure(pairId, detail, needsUser = true)
            return RunResult.NeedsUser(detail)
        }

        private suspend fun SyncOutcome.toResult(pairId: String): RunResult =
            when (this) {
                is SyncOutcome.Paused -> {
                    RunResult.NeedsUser(pairs.pair(pairId)?.statusDetail ?: reason.toString())
                }

                is SyncOutcome.NeedsDeleteConfirmation -> {
                    RunResult.NeedsUser("$deleteCount of $pairFileCount files would be deleted")
                }

                is SyncOutcome.Completed -> {
                    val results = report.results
                    RunResult.Completed(
                        transfers = results.count { it.outcome == ActionOutcome.Done },
                        newConflicts = plan.conflicts.size,
                        failed = results.count { it.outcome is ActionOutcome.Failed },
                    )
                }
            }
    }

/** A pair paused because Google access lapsed or was revoked (FR-A4); the UI offers to grant it again. */
object GoogleAccess {
    const val SIGN_IN_AGAIN = "Sign in to Google again"

    fun isNeeded(statusDetail: String?): Boolean = statusDetail?.startsWith(SIGN_IN_AGAIN) == true
}
