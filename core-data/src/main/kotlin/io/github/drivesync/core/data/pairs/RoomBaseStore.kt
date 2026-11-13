package io.github.drivesync.core.data.pairs

import io.github.drivesync.core.data.db.BaseDao
import io.github.drivesync.core.data.db.BaseEntryEntity
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.store.BaseStore
import io.github.drivesync.core.sync.store.BaseUpdate

/** The base snapshot of one pair, persisted in Room (PRD 3.9 rules 2 and 7). */
class RoomBaseStore(
    private val dao: BaseDao,
    private val pairId: String,
) : BaseStore {
    override suspend fun load(): BaseSnapshot? {
        if (dao.hasBase(pairId) != true) return null
        return BaseSnapshot(dao.entries(pairId).map { it.toModel() })
    }

    override suspend fun commit(updates: List<BaseUpdate>) {
        // Later updates win, as if applied one by one.
        val latest = linkedMapOf<String, BaseUpdate>()
        updates.forEach { update ->
            val path =
                when (update) {
                    is BaseUpdate.Put -> update.entry.path.value
                    is BaseUpdate.Remove -> update.path.value
                }
            latest.remove(path)
            latest[path] = update
        }
        val puts = latest.values.filterIsInstance<BaseUpdate.Put>().map { it.entry.toEntity() }
        val removes = latest.values.filterIsInstance<BaseUpdate.Remove>().map { it.path.value }
        dao.apply(pairId, puts, removes)
    }

    private fun BaseEntryEntity.toModel() =
        BaseEntry(
            path = RelPath.of(path),
            isFolder = isFolder,
            md5 = md5,
            localSize = localSize,
            localLastModified = localLastModified,
            remoteFileId = remoteFileId,
            remoteVersion = remoteVersion,
        )

    private fun BaseEntry.toEntity() =
        BaseEntryEntity(
            pairId = pairId,
            path = path.value,
            isFolder = isFolder,
            md5 = md5,
            localSize = localSize,
            localLastModified = localLastModified,
            remoteFileId = remoteFileId,
            remoteVersion = remoteVersion,
        )
}
