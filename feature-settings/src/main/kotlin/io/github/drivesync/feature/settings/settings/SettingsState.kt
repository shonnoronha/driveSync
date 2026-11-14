package io.github.drivesync.feature.settings.settings

import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.ThemeMode

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val defaultDeviceName: String = "",
    val version: String = "",
    val signedOut: Boolean = false,
)

/** Callbacks of the settings screen. */
data class SettingsActions(
    val onSignOut: () -> Unit = {},
    val onInterval: (Int) -> Unit = {},
    val onNetworkRules: (wifiOnly: Boolean, chargingOnly: Boolean) -> Unit = { _, _ -> },
    val onDeviceName: (String) -> Unit = {},
    val onTheme: (ThemeMode, Boolean) -> Unit = { _, _ -> },
    val onAccent: (seedColor: Int, amoled: Boolean) -> Unit = { _, _ -> },
    val onLogRetention: (Int) -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    val onOpenBattery: () -> Unit = {},
    val onOpenSource: () -> Unit = {},
    val onReportProblem: () -> Unit = {},
)

object SettingsChoices {
    /** WorkManager's minimum period is 15 minutes (PRD 3.4). */
    val INTERVALS = listOf(15, 30, 60, 120, 360, 720, 1440)
    val LOG_RETENTION_DAYS = listOf(7, 30, 90, 365)

    /** The public repository: About links to it and to its issues. */
    const val SOURCE_URL = "https://github.com/shonnoronha/driveSync"
    const val ISSUES_URL = "$SOURCE_URL/issues"
}
