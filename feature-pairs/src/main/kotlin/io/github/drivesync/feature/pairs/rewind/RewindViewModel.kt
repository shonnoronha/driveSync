package io.github.drivesync.feature.pairs.rewind

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.history.HistoryRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.work.android.RewindWork
import io.github.drivesync.core.work.android.SyncScheduler
import io.github.drivesync.feature.pairs.detail.PairDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Rewind for one folder: pick a point of its history, preview, confirm. The rewind itself runs in WorkManager. */
@HiltViewModel
class RewindViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        pairs: PairRepository,
        history: HistoryRepository,
        workManager: WorkManager,
        private val scheduler: SyncScheduler,
    ) : ViewModel() {
        private val pairId: String = checkNotNull(savedState[PairDetailViewModel.PAIR_ID]) { "pairId missing" }
        private val selected = MutableStateFlow<Long?>(null)

        private val lastRewind =
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.rewindWorkName(pairId)).map { it.lastOrNull() }

        val state: StateFlow<RewindUiState> =
            combine(
                pairs.observePairs().map { list -> list.firstOrNull { it.id == pairId }?.name.orEmpty() },
                history.observeTimeline(pairId),
                selected,
                lastRewind,
            ) { name, timeline, time, work ->
                val running = work != null && !work.state.isFinished
                val result = work?.takeIf { it.state == WorkInfo.State.SUCCEEDED }?.outputData?.let(RewindWork::read)
                rewindState(name, timeline, time, running, result)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RewindUiState())

        fun select(time: Long) {
            selected.value = time
        }

        fun rewind() {
            val current = state.value
            val point = current.selectedPoint ?: return
            if (current.canRewind) scheduler.rewind(pairId, point.time)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

@Composable
fun RewindRoute(
    onBack: () -> Unit,
    viewModel: RewindViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RewindScreen(state, onBack = onBack, onSelect = viewModel::select, onRewind = viewModel::rewind)
}
