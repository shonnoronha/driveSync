package io.github.drivesync.feature.pairs.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.components.ConnectedToggleGroup
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.StatusChip
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.activity.ActivityRowItem
import io.github.drivesync.feature.pairs.R
import io.github.drivesync.feature.pairs.add.AddPairRules
import io.github.drivesync.feature.pairs.add.label
import io.github.drivesync.feature.pairs.home.PairItem
import io.github.drivesync.feature.pairs.home.PairState
import io.github.drivesync.feature.pairs.home.label
import io.github.drivesync.feature.pairs.home.tone

@Composable
internal fun StatusSection(
    pair: PairItem,
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    val container =
        when (pair.state) {
            PairState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.errorContainer
            PairState.CONFIRM_DELETES -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    DsCard(Modifier.padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm), container = container) {
        Column(Modifier.padding(Tokens.rowPaddingH), verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            StatusChip(pair.state.label(), pair.state.tone())
            pair.detail
                ?.takeIf { pair.state == PairState.NEEDS_ATTENTION || pair.state == PairState.CONFIRM_DELETES }
                ?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            when (pair.state) {
                PairState.NEEDS_ATTENTION -> {
                    AttentionActions(actions)
                }

                PairState.CONFIRM_DELETES -> {
                    ConfirmDeletesActions(actions)
                }

                PairState.PAUSED -> {
                    // Paused: the "Sync this folder" switch below resumes it.
                }

                else -> {
                    FilledTonalButton(onClick = actions.onSyncNow, enabled = pair.state != PairState.SYNCING) {
                        Text(stringResource(R.string.pair_sync_now))
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AttentionActions(actions: PairDetailActions) {
    Text(stringResource(R.string.detail_attention_hint), style = MaterialTheme.typography.bodyMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm),
        verticalArrangement = Arrangement.spacedBy(Tokens.gapSm),
    ) {
        Button(onClick = actions.onSyncNow) { Text(stringResource(R.string.detail_try_again)) }
        OutlinedButton(onClick = actions.onChooseFolderAgain) { Text(stringResource(R.string.detail_choose_folder)) }
        OutlinedButton(onClick = actions.onRecreateDriveFolder) { Text(stringResource(R.string.detail_recreate_drive)) }
    }
}

@Composable
private fun ConfirmDeletesActions(actions: PairDetailActions) {
    Text(stringResource(R.string.detail_confirm_hint), style = MaterialTheme.typography.bodyMedium)
    Row {
        Button(
            onClick = actions.onConfirmDeletes,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.detail_delete_them)) }
        Spacer(Modifier.width(Tokens.gapSm))
        OutlinedButton(onClick = actions.onRestoreInstead) { Text(stringResource(R.string.detail_restore_them)) }
    }
}

@Composable
internal fun FolderSection(pair: PairItem) {
    SettingsSectionHeader(stringResource(R.string.detail_folder))
    SettingsGroup {
        item {
            SettingsRow(
                title = (if (pair.onInternalStorage) "" else "SD card/") + pair.phonePath,
                subtitle = stringResource(R.string.detail_on_phone),
                icon = if (pair.onInternalStorage) Sym.Smartphone else Sym.SdCard,
            )
        }
        item {
            SettingsRow(
                title = "Drive Sync/${pair.name}",
                subtitle = stringResource(R.string.detail_on_drive),
                icon = Sym.CloudSync,
            )
        }
    }
}

@Composable
internal fun OptionsSection(
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    SettingsSectionHeader(stringResource(R.string.detail_options))
    SettingsGroup {
        item {
            SettingsSwitchRow(
                stringResource(R.string.detail_sync_enabled),
                state.enabled,
                actions.onEnabled,
                subtitle = stringResource(R.string.detail_sync_enabled_body),
                icon = Sym.PauseCircle,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.detail_direction),
                icon = Sym.SyncAlt,
                footer = {
                    ConnectedToggleGroup(SyncDirection.entries, state.settings.direction, actions.onDirection) {
                        Text(it.shortLabel(), maxLines = 1)
                    }
                },
            )
        }
        if (AddPairRules.mirrorAllowed(state.settings.direction)) {
            item {
                SettingsSwitchRow(
                    stringResource(R.string.add_mirror),
                    state.settings.mirrorDeletions,
                    actions.onMirror,
                    subtitle = stringResource(R.string.add_mirror_body),
                    icon = Sym.Delete,
                )
            }
        }
        item { PolicyRow(state.settings.conflictPolicy, actions.onPolicy) }
    }
}

@Composable
private fun PolicyRow(
    policy: ConflictPolicy,
    onPolicy: (ConflictPolicy) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = stringResource(R.string.add_conflicts),
            subtitle = policy.label(),
            icon = Sym.Difference,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConflictPolicy.entries.forEach {
                DropdownMenuItem(text = { Text(it.label()) }, onClick = {
                    onPolicy(it)
                    open = false
                })
            }
        }
    }
}

@Composable
internal fun RecentSection(
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    SettingsSectionHeader(stringResource(R.string.detail_recent))
    SettingsGroup {
        if (state.recent.isEmpty()) {
            item { SettingsRow(title = stringResource(R.string.detail_no_activity), icon = Sym.History) }
        }
        state.recent.forEach { row -> item { ActivityRowItem(row, showPair = false) } }
        item {
            SettingsRow(
                title = stringResource(R.string.detail_see_all),
                icon = Sym.History,
                onClick = actions.onOpenActivity,
            )
        }
    }
    if (state.skippedFiles > 0) {
        Text(
            stringResource(R.string.detail_skipped, state.skippedFiles),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Tokens.pageInset + Tokens.gapLg, vertical = Tokens.gapSm),
        )
    }
}

@Composable
internal fun RemoveSection(
    name: String,
    actions: PairDetailActions,
) {
    var asking by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { asking = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.pageInset, vertical = Tokens.gapXl),
    ) { Text(stringResource(R.string.detail_remove)) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.detail_remove_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
                    Text(stringResource(R.string.detail_remove_body))
                    Row(
                        Modifier.toggleable(trash, role = Role.Checkbox) { trash = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = trash, onCheckedChange = null)
                        Spacer(Modifier.width(Tokens.gapSm))
                        Text(stringResource(R.string.detail_remove_trash))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    actions.onRemove(trash)
                }) { Text(stringResource(R.string.detail_remove_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { asking = false },
                ) { Text(stringResource(R.string.detail_cancel)) }
            },
        )
    }
}

@Composable
private fun SyncDirection.shortLabel(): String =
    stringResource(
        when (this) {
            SyncDirection.TWO_WAY -> R.string.direction_short_two_way
            SyncDirection.UPLOAD_ONLY -> R.string.direction_short_upload
            SyncDirection.DOWNLOAD_ONLY -> R.string.direction_short_download
        },
    )
