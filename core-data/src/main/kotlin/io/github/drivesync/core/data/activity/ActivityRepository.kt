package io.github.drivesync.core.data.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ConflictEntity
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.db.SkippedEntity
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

        /** Deletes log entries older than [before] (epoch millis); returns how many. */
        suspend fun prune(before: Long): Int = db.activity().deleteOlderThan(before)

        private companion object {
            const val DEFAULT_LIMIT = 500
        }
    }
