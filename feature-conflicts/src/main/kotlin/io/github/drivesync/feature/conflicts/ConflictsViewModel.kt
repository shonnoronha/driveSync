package io.github.drivesync.feature.conflicts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.work.android.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConflictsViewModel
    @Inject
    constructor(
        private val activity: ActivityRepository,
        pairs: PairRepository,
        private val scheduler: SyncScheduler,
    ) : ViewModel() {
        val state: StateFlow<ConflictsUiState> =
            combine(
                activity.observeConflicts(),
                activity.observeResolutions(),
                pairs.observePairs(),
            ) { conflicts, answers, list ->
                conflictsState(conflicts, answers, list.associate { it.id to it.name })
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConflictsUiState())

        /** Stores the answer and syncs that pair right away, so the choice takes effect. */
        fun choose(
            item: ConflictItem,
            policy: ConflictPolicy,
        ) {
            viewModelScope.launch {
                activity.resolve(item.pairId, item.path, policy)
                scheduler.syncNow(item.pairId)
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

@Composable
fun ConflictsRoute(viewModel: ConflictsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConflictsScreen(state, ConflictsActions(onChoose = viewModel::choose))
}
