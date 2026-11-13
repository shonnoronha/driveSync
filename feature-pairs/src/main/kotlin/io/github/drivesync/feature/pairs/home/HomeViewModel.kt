package io.github.drivesync.feature.pairs.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.work.android.SyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        pairs: PairRepository,
        activity: ActivityRepository,
        workManager: WorkManager,
        private val scheduler: SyncScheduler,
    ) : ViewModel() {
        private val running =
            workManager.getWorkInfosByTagFlow(SyncScheduler.TAG).map { infos ->
                infos.filter { it.state == WorkInfo.State.RUNNING }
            }

        /** Re-renders "synced 5 min ago" every minute. */
        private val clock =
            flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(MINUTE_MILLIS)
                }
            }

        val state: StateFlow<HomeUiState> =
            combine(pairs.observePairs(), activity.observeConflicts(), running, clock) { list, conflicts, work, _ ->
                // The ticker only triggers a redraw each minute; the time itself is read fresh, so a sync that just
                // finished is never "in the future".
                val now = System.currentTimeMillis()
                val pairRuns = work.mapNotNull { info -> SyncScheduler.pairIdOf(info.tags) }
                val allRuns = work.any { info -> SyncScheduler.pairIdOf(info.tags) == null }
                homeState(list, conflicts.size, pairRuns.toSet(), syncingAll = allRuns, now = now)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

        fun syncAll() = scheduler.syncNow()

        fun syncPair(pairId: String) = scheduler.syncNow(pairId)

        private companion object {
            const val MINUTE_MILLIS = 60_000L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
