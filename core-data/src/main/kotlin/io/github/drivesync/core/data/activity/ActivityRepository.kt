package io.github.drivesync.core.data.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ConflictEntity
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.db.ResolutionEntity
import io.github.drivesync.core.data.db.SkippedEntity
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.RelPath
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Read side of the activity log, conflicts and skipped files, plus log retention (PRD 4.5). */
class ActivityRepository
    @Inject
    constructor(
        private val db: DriveSyncDatabase,
    ) {
        fun observe(
            pairId: String? = null,
            limit: Int = DEFAULT_LIMIT,
        ): Flow<List<ActivityEntity>> =
            if (pairId ==
                null
            ) {
                db.activity().observeAll(limit)
            } else {
                db.activity().observePair(pairId, limit)
            }

        fun observeConflicts(): Flow<List<ConflictEntity>> = db.pairItems().observeConflicts()

        fun observeSkipped(pairId: String): Flow<List<SkippedEntity>> = db.pairItems().observeSkipped(pairId)

        fun observeResolutions(): Flow<List<ResolutionEntity>> = db.resolutions().observeResolutions()

        /** Records the user's answer to a pending conflict; the pair's next run applies it. */
        suspend fun resolve(
            pairId: String,
            path: String,
            policy: ConflictPolicy,
        ) = db.resolutions().resolve(ResolutionEntity(pairId, path, policy))

        /** Answers for the next run of [pairId]. */
        suspend fun resolutions(pairId: String): Map<RelPath, ConflictPolicy> =
            db.resolutions().resolutions(pairId).associate { RelPath.of(it.path) to it.policy }

        /** Called after a completed run: the answers were applied (or are moot). */
        suspend fun clearResolutions(pairId: String) = db.resolutions().clearResolutions(pairId)

        /** Deletes log entries older than [before] (epoch millis); returns how many. */
        suspend fun prune(before: Long): Int = db.activity().deleteOlderThan(before)

        private companion object {
            const val DEFAULT_LIMIT = 500
        }
    }
