package io.github.drivesync.feature.settings.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.feature.settings.R

@Composable
internal fun AppearanceSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val s = state.settings
    Section(stringResource(R.string.settings_appearance)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = ThemeMode.entries
            modes.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.theme == mode,
                    onClick = { actions.onTheme(mode, s.dynamicColor) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(mode.label()) }
            }
        }
        SwitchRow(
            stringResource(R.string.settings_dynamic_color),
            null,
            s.dynamicColor,
        ) { actions.onTheme(s.theme, it) }
    }
}

@Composable
internal fun MoreSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    Section(stringResource(R.string.settings_other)) {
        Picker(
            stringResource(R.string.settings_log_retention),
            state.settings.logRetentionDays,
            SettingsChoices.LOG_RETENTION_DAYS,
            { stringResource(R.string.settings_days, it) },
            actions.onLogRetention,
        )
        TextButton(onClick = actions.onOpenNotifications) { Text(stringResource(R.string.settings_notifications)) }
        TextButton(onClick = actions.onOpenBattery) { Text(stringResource(R.string.settings_battery)) }
    }
    Section(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.settings_version, state.version), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = actions.onOpenSource) { Text(stringResource(R.string.settings_source)) }
        TextButton(onClick = actions.onReportProblem) { Text(stringResource(R.string.settings_report)) }
    }
}

@Composable
private fun ThemeMode.label(): String =
    stringResource(
        when (this) {
            ThemeMode.SYSTEM -> R.string.settings_theme_system
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )
