package io.github.drivesync.core.data.pairs

import io.github.drivesync.core.data.db.PairEntity
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.sync.model.MassDeleteGuard
import io.github.drivesync.core.sync.model.PairSettings

/** A folder pair as the app sees it. */
data class SyncPair(
    val id: String,
    val name: String,
    val localTreeUri: String,
    val driveFolderId: String,
    val settings: PairSettings,
    val enabled: Boolean,
    val status: PairStatus,
    val statusDetail: String?,
    val lastSyncAt: Long?,
)

internal fun PairEntity.toModel() =
    SyncPair(
        id = id,
        name = name,
        localTreeUri = localTreeUri,
        driveFolderId = driveFolderId,
        settings =
            PairSettings(
                direction = direction,
                conflictPolicy = conflictPolicy,
                mirrorDeletions = mirrorDeletions,
                massDeleteGuard = MassDeleteGuard(massDeleteMinFiles, massDeleteMinFraction),
            ),
        enabled = enabled,
        status = status,
        statusDetail = statusDetail,
        lastSyncAt = lastSyncAt,
    )
