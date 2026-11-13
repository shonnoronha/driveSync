package io.github.drivesync.feature.settings.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.drivesync.feature.settings.R

/** Settings and About (PRD 4.5, 8.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
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
            HorizontalDivider()
            SyncSection(state, actions)
            HorizontalDivider()
            AppearanceSection(state, actions)
            HorizontalDivider()
            MoreSection(state, actions)
        }
    }
}

@Composable
internal fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var asking by remember { mutableStateOf(false) }
    Section(stringResource(R.string.settings_account)) {
        Text(state.settings.accountEmail.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { asking = true }) { Text(stringResource(R.string.settings_sign_out)) }
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
    Section(stringResource(R.string.settings_sync)) {
        Picker(stringResource(R.string.settings_interval), s.syncIntervalMinutes, SettingsChoices.INTERVALS, {
            intervalLabel(it)
        }, actions.onInterval)
        SwitchRow(
            stringResource(R.string.settings_wifi_only),
            stringResource(R.string.settings_wifi_only_body),
            s.wifiOnly,
        ) {
            actions.onNetworkRules(it, s.chargingOnly)
        }
        SwitchRow(stringResource(R.string.settings_charging_only), null, s.chargingOnly) {
            actions.onNetworkRules(s.wifiOnly, it)
        }
        DeviceNameField(s.deviceName, state.defaultDeviceName, actions.onDeviceName)
    }
}

@Composable
private fun DeviceNameField(
    current: String?,
    fallback: String,
    onChange: (String) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(R.string.settings_device_name)) },
        placeholder = { Text(fallback) },
        supportingText = { Text(stringResource(R.string.settings_device_name_body, current ?: fallback)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun <T> Picker(
    title: String,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    label(value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach {
                DropdownMenuItem(text = { Text(label(it)) }, onClick = {
                    open = false
                    onPick(it)
                })
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    body: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    when {
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.settings_minutes, minutes)
        minutes == MINUTES_PER_HOUR -> stringResource(R.string.settings_hour)
        else -> stringResource(R.string.settings_hours, minutes / MINUTES_PER_HOUR)
    }

private const val MINUTES_PER_HOUR = 60
