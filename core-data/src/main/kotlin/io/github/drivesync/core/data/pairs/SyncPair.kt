package io.github.drivesync.core.data.pairs

import io.github.drivesync.core.data.db.PairEntity
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.sync.model.MassDeleteGuard
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RunConditions

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
                filters =
                    FileFilters(
                        exclude = excludePatterns?.lines()?.filter { it.isNotBlank() }.orEmpty(),
                        maxFileSize = maxFileSize,
                        skipHidden = skipHidden ?: false,
                        includeSubfolders = includeSubfolders ?: true,
                    ),
                conditions = RunConditions(wifiOnly = wifiOnly ?: false, chargingOnly = chargingOnly ?: false),
            ),
        enabled = enabled,
        status = status,
        statusDetail = statusDetail,
        lastSyncAt = lastSyncAt,
    )

/** Writes [settings] into the entity's settings columns. */
internal fun PairEntity.withSettings(settings: PairSettings): PairEntity =
    copy(
        direction = settings.direction,
        conflictPolicy = settings.conflictPolicy,
        mirrorDeletions = settings.mirrorDeletions,
        massDeleteMinFiles = settings.massDeleteGuard.minFiles,
        massDeleteMinFraction = settings.massDeleteGuard.minFraction,
        excludePatterns =
            settings.filters.exclude
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .ifEmpty { null },
        maxFileSize = settings.filters.maxFileSize,
        skipHidden = settings.filters.skipHidden.takeIf { it },
        includeSubfolders = settings.filters.includeSubfolders.takeIf { !it },
        wifiOnly = settings.conditions.wifiOnly.takeIf { it },
        chargingOnly = settings.conditions.chargingOnly.takeIf { it },
    )
