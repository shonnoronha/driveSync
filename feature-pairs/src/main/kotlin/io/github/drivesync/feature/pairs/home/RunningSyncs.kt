package io.github.drivesync.feature.pairs.home

import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.drivesync.core.work.android.PairProgress
import io.github.drivesync.core.work.android.SyncScheduler
import io.github.drivesync.core.work.android.WorkProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The syncs WorkManager is running right now: per pair, for all pairs, and how far each pair has got. */
internal data class RunningSyncs(
    val pairIds: Set<String> = emptySet(),
    val all: Boolean = false,
    val progress: Map<String, PairProgress> = emptyMap(),
)

internal fun WorkManager.runningSyncs(): Flow<RunningSyncs> =
    getWorkInfosByTagFlow(SyncScheduler.TAG).map { infos ->
        val running = infos.filter { it.state == WorkInfo.State.RUNNING }
        RunningSyncs(
            pairIds = running.mapNotNull { SyncScheduler.pairIdOf(it.tags) }.toSet(),
            all = running.any { SyncScheduler.pairIdOf(it.tags) == null },
            progress = running.mapNotNull { WorkProgress.read(it.progress) }.associateBy { it.pairId },
        )
    }
