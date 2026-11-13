package io.github.drivesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.SettingsRepository
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
    ) : ViewModel() {
        /** Null until settings are loaded, so the first screen is not guessed. */
        val settingsState: StateFlow<AppSettings?> =
            settings.settings.map<AppSettings, AppSettings?> { it }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                null,
            )

        /** PRD 3.4 (12): opening the app triggers a quick catch-up sync. */
        fun onAppVisible() {
            viewModelScope.launch {
                if (settings.current().accountEmail != null) scheduler.syncNow()
            }
        }
    }
