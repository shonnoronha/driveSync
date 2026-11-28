package io.github.drivesync.core.data.history

import io.github.drivesync.core.data.db.BaseHistoryEntity
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.sync.model.BaseChange
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseTimeline
import io.github.drivesync.core.sync.model.RelPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** A pair's synced history, run by run (Rewind). Recorded by the base store; read here. */
class HistoryRepository
    @Inject
    constructor(
        private val db: DriveSyncDatabase,
    ) {
        fun observeTimeline(pairId: String): Flow<BaseTimeline> = db.base().observeHistory(pairId).map(::timelineOf)

        suspend fun timeline(pairId: String): BaseTimeline = timelineOf(db.base().history(pairId))

        /**
         * Points older than [before] collapse into one starting point, the state they added up to. Drive keeps old
         * versions for about 30 days, so older points could rarely be restored anyway.
         */
        suspend fun prune(before: Long) {
            for (pairId in db.base().historyPairs()) {
                val old = db.base().history(pairId).filter { it.runAt < before }
                if (old.map { it.runAt }.distinct().size <= 1) continue
                val origin = old.maxOf { it.runAt }
                val state = timelineOf(old).stateAsOf(origin)
                db.base().collapseHistory(pairId, before, state.values.map { it.toHistory(pairId, origin) })
            }
        }

        private fun timelineOf(rows: List<BaseHistoryEntity>) = BaseTimeline(rows.map { it.toChange() })

        private fun BaseHistoryEntity.toChange(): BaseChange {
            val relPath = RelPath.of(path)
            val entry =
                if (!present) {
                    null
                } else {
                    BaseEntry(
                        path = relPath,
                        isFolder = isFolder,
                        md5 = md5,
                        localSize = localSize,
                        localLastModified = localLastModified,
                        remoteFileId = remoteFileId,
                        remoteVersion = remoteVersion,
                        remotePath = remotePath?.let(RelPath::of),
                    )
                }
            return BaseChange(runAt, relPath, entry)
        }

        private fun BaseEntry.toHistory(
            pairId: String,
            runAt: Long,
        ) = BaseHistoryEntity(
            pairId = pairId,
            runAt = runAt,
            path = path.value,
            present = true,
            isFolder = isFolder,
            md5 = md5,
            localSize = localSize,
            localLastModified = localLastModified,
            remoteFileId = remoteFileId,
            remoteVersion = remoteVersion,
            remotePath = remotePath?.value,
        )
    }
