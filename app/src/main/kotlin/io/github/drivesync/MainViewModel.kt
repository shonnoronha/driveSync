package io.github.drivesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.work.android.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide UI state: theme and whether someone is signed in. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val scheduler: SyncScheduler,
        activity: ActivityRepository,
    ) : ViewModel() {
        /** Conflicts waiting for the user, for the navigation badge. */
        val pendingConflicts: StateFlow<Int> =
            activity
                .observeConflicts()
                .map { list -> list.count { it.resolution == ConflictResolution.PENDING } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

        /** Null until settings are loaded, so the first screen is not guessed. */
        val settingsState: StateFlow<AppSettings?> =
            settings.settings.map<AppSettings, AppSettings?> { it }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                null,
            )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }

        /** PRD 3.4 (12): opening the app triggers a quick catch-up sync. */
        fun onAppVisible() {
            viewModelScope.launch {
                if (settings.current().accountEmail != null) scheduler.syncNow()
            }
        }
    }
