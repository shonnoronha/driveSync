package io.github.drivesync.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel
    @Inject
    constructor(
        activity: ActivityRepository,
        pairs: PairRepository,
    ) : ViewModel() {
        private val filter = MutableStateFlow(ActivityFilter.ALL)

        val state: StateFlow<ActivityUiState> =
            combine(activity.observe(), pairs.observePairs(), filter) { entries, list, selected ->
                val zone = ZoneId.systemDefault()
                ActivityUiState(
                    loading = false,
                    filter = selected,
                    days = activityDays(entries, list.associate { it.id to it.name }, selected, zone),
                    today = LocalDate.now(zone),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ActivityUiState())

        fun setFilter(value: ActivityFilter) {
            filter.value = value
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

@Composable
fun ActivityRoute(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActivityScreen(state, viewModel::setFilter, onBack)
}
