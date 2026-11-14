package io.github.drivesync.feature.settings.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.RootTopBar
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.feature.settings.R

/** Settings and About (PRD 4.5, 8.7), in connected groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { RootTopBar(stringResource(R.string.settings_title), scroll) },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            AccountSection(state, actions)
            SyncSection(state, actions)
            AppearanceSection(state, actions)
            MoreSection(state, actions)
        }
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var asking by remember { mutableStateOf(false) }
    SettingsSectionHeader(stringResource(R.string.settings_account))
    SettingsGroup {
        item {
            SettingsRow(
                title = state.settings.accountEmail.orEmpty(),
                subtitle = stringResource(R.string.settings_signed_in),
                icon = Sym.AccountCircle,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_sign_out),
                icon = Sym.Logout,
                iconTint = MaterialTheme.colorScheme.error,
                onClick = { asking = true },
            )
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    actions.onSignOut()
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { asking = false },
                ) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun SyncSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val s = state.settings
    var editingName by remember { mutableStateOf(false) }
    SettingsSectionHeader(stringResource(R.string.settings_sync))
    SettingsGroup {
        item {
            MenuRow(
                stringResource(
                    R.string.settings_interval,
                ),
                Sym.Schedule,
                s.syncIntervalMinutes,
                SettingsChoices.INTERVALS,
                {
                    intervalLabel(it)
                },
                actions.onInterval,
            )
        }
        item {
            SettingsSwitchRow(
                stringResource(R.string.settings_wifi_only),
                s.wifiOnly,
                { actions.onNetworkRules(it, s.chargingOnly) },
                subtitle = stringResource(R.string.settings_wifi_only_body),
                icon = Sym.Wifi,
            )
        }
        item {
            SettingsSwitchRow(
                stringResource(R.string.settings_charging_only),
                s.chargingOnly,
                { actions.onNetworkRules(s.wifiOnly, it) },
                icon = Sym.Bolt,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_device_name),
                subtitle = s.deviceName ?: state.defaultDeviceName,
                icon = Sym.Smartphone,
                onClick = { editingName = true },
            )
        }
    }
    if (editingName) {
        DeviceNameDialog(s.deviceName ?: state.defaultDeviceName, onDismiss = { editingName = false }) {
            actions.onDeviceName(it)
            editingName = false
        }
    }
}

@Composable
private fun MoreSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsSectionHeader(stringResource(R.string.settings_other))
    SettingsGroup {
        item {
            MenuRow(
                stringResource(R.string.settings_log_retention),
                Sym.Article,
                state.settings.logRetentionDays,
                SettingsChoices.LOG_RETENTION_DAYS,
                { stringResource(R.string.settings_days, it) },
                actions.onLogRetention,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_body),
                icon = Sym.Notifications,
                onClick = actions.onOpenNotifications,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(R.string.settings_battery_body),
                icon = Sym.Battery,
                onClick = actions.onOpenBattery,
            )
        }
    }
    SettingsSectionHeader(stringResource(R.string.settings_about))
    SettingsGroup {
        item { SettingsRow(title = stringResource(R.string.settings_version, state.version), icon = Sym.Info) }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_source),
                icon = Sym.Code,
                onClick = actions.onOpenSource,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.settings_report),
                icon = Sym.BugReport,
                onClick = actions.onReportProblem,
            )
        }
    }
}

@Composable
private fun DeviceNameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_device_name_dialog)) },
        text = {
            Column {
                io.github.drivesync.core.ui.components.DsTextField(
                    text,
                    { text = it },
                    stringResource(R.string.settings_device_name),
                )
                Text(
                    stringResource(R.string.settings_device_name_body, text.ifBlank { current }),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
