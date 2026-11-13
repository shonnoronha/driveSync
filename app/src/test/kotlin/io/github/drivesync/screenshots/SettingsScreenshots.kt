package io.github.drivesync.screenshots

import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.feature.settings.settings.SettingsActions
import io.github.drivesync.feature.settings.settings.SettingsScreen
import io.github.drivesync.feature.settings.settings.SettingsUiState
import org.junit.Test

class SettingsScreenshots : Screenshots() {
    private val state =
        SettingsUiState(
            loading = false,
            settings =
                AppSettings(
                    accountEmail = "you@gmail.com",
                    syncIntervalMinutes = 60,
                    wifiOnly = true,
                    theme = ThemeMode.SYSTEM,
                ),
            defaultDeviceName = "Pixel 9",
            version = "0.1.0",
        )

    @Test
    fun settings() = snap("settings") { SettingsScreen(state, SettingsActions()) }

    @Test
    fun settingsAccent() =
        snap("settings_accent", seed = io.github.drivesync.core.ui.theme.SeedColors.PURPLE) {
            SettingsScreen(
                state.copy(
                    settings =
                        state.settings.copy(
                            dynamicColor = false,
                            seedColor = io.github.drivesync.core.ui.theme.SeedColors.PURPLE,
                        ),
                ),
                SettingsActions(),
            )
        }

    @Test
    fun settingsDark() = snap("settings_dark", dark = true) { SettingsScreen(state, SettingsActions()) }
}
