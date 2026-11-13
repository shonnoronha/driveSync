package io.github.drivesync.core.data.pairs

import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.db.PairEntity
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.store.BaseStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/** Folder pairs and their base snapshots. */
class PairRepository(
    private val db: DriveSyncDatabase,
    private val clock: () -> Long,
    private val newId: () -> String,
) {
    @Inject
    constructor(db: DriveSyncDatabase) : this(db, System::currentTimeMillis, { UUID.randomUUID().toString() })

    fun observePairs(): Flow<List<SyncPair>> = db.pairs().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun pairs(): List<SyncPair> = db.pairs().all().map { it.toModel() }

    suspend fun pair(id: String): SyncPair? = db.pairs().get(id)?.toModel()

    /** A new id for a pair about to be created; the Drive folder is tagged with it (PRD 3.10). */
    fun newPairId(): String = newId()

    /** Adds a pair. It has no base, so its first run only merges (link / copy / keep-both, rule 7). */
    suspend fun add(
        id: String,
        name: String,
        localTreeUri: String,
        driveFolderId: String,
        settings: PairSettings = PairSettings(),
    ): SyncPair {
        val entity =
            PairEntity(
                id = id,
                name = name,
                localTreeUri = localTreeUri,
                driveFolderId = driveFolderId,
                direction = settings.direction,
                conflictPolicy = settings.conflictPolicy,
                mirrorDeletions = settings.mirrorDeletions,
                massDeleteMinFiles = settings.massDeleteGuard.minFiles,
                massDeleteMinFraction = settings.massDeleteGuard.minFraction,
                enabled = true,
                hasBase = false,
                status = PairStatus.IDLE,
                statusDetail = null,
                lastSyncAt = null,
                createdAt = clock(),
            )
        db.pairs().upsert(entity)
        return entity.toModel()
    }

    suspend fun updateSettings(
        id: String,
        settings: PairSettings,
    ) {
        val current = db.pairs().get(id) ?: return
        db.pairs().upsert(
            current.copy(
                direction = settings.direction,
                conflictPolicy = settings.conflictPolicy,
                mirrorDeletions = settings.mirrorDeletions,
                massDeleteMinFiles = settings.massDeleteGuard.minFiles,
                massDeleteMinFraction = settings.massDeleteGuard.minFraction,
            ),
        )
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = db.pairs().setEnabled(id, enabled)

    /** Points a pair at another Drive folder (PRD 3.8 "recreate"): the base is dropped so nothing gets deleted. */
    suspend fun relink(
        id: String,
        driveFolderId: String,
    ) {
        val current = db.pairs().get(id) ?: return
        db.pairs().upsert(current.copy(driveFolderId = driveFolderId))
        db.base().reset(id)
    }

    /**
     * Points the pair at a re-picked phone folder (after access was lost). The base is dropped, so the next run
     * merges: nothing is deleted on Drive even if the new folder lacks files.
     */
    suspend fun changeLocalFolder(
        id: String,
        treeUri: String,
    ) {
        db.pairs().setTreeUri(id, treeUri)
        db.base().reset(id)
    }

    /**
     * "Don't delete, restore" after a mass-delete warning (FR-S6): dropping the base makes the next run merge,
     * so the missing files are copied back instead of deleted on the other side.
     */
    suspend fun restoreInsteadOfDelete(id: String) = db.base().reset(id)

    /** Stops syncing and forgets the pair's records. Never touches files on either side (PRD 3.8). */
    suspend fun remove(id: String) = db.pairs().delete(id)

    fun baseStore(pairId: String): BaseStore = RoomBaseStore(db.base(), pairId)
}
