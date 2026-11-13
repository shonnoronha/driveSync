package io.github.drivesync.feature.pairs.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.components.StatusChip
import io.github.drivesync.feature.activity.ActivityRowItem
import io.github.drivesync.feature.pairs.R
import io.github.drivesync.feature.pairs.add.AddPairRules
import io.github.drivesync.feature.pairs.add.label
import io.github.drivesync.feature.pairs.home.PairItem
import io.github.drivesync.feature.pairs.home.PairState
import io.github.drivesync.feature.pairs.home.label
import io.github.drivesync.feature.pairs.home.tone

private val sectionPadding = Modifier.padding(horizontal = 16.dp)

@Composable
internal fun StatusSection(
    pair: PairItem,
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    Column(sectionPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(pair.state.label(), pair.state.tone())
        val phone = (if (pair.onInternalStorage) "Phone/" else "SD card/") + pair.phonePath
        Text(phone, style = MaterialTheme.typography.bodyMedium)
        Text("Drive Sync/${pair.name}", style = MaterialTheme.typography.bodyMedium)
        when (pair.state) {
            PairState.NEEDS_ATTENTION -> {
                AttentionCard(pair.detail, actions)
            }

            PairState.CONFIRM_DELETES -> {
                ConfirmDeletesCard(pair.detail, actions)
            }

            PairState.PAUSED -> {
                // Paused: the switch below resumes it.
            }

            else -> {
                FilledTonalButton(onClick = actions.onSyncNow, enabled = pair.state != PairState.SYNCING) {
                    Text(stringResource(R.string.pair_sync_now))
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.skippedFiles > 0) {
            Text(
                stringResource(R.string.detail_skipped, state.skippedFiles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttentionCard(
    detail: String?,
    actions: PairDetailActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(stringResource(R.string.detail_attention_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = actions.onSyncNow) { Text(stringResource(R.string.detail_try_again)) }
            OutlinedButton(
                onClick = actions.onChooseFolderAgain,
            ) { Text(stringResource(R.string.detail_choose_folder)) }
            OutlinedButton(
                onClick = actions.onRecreateDriveFolder,
            ) { Text(stringResource(R.string.detail_recreate_drive)) }
        }
    }
}

@Composable
private fun ConfirmDeletesCard(
    detail: String?,
    actions: PairDetailActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(stringResource(R.string.detail_confirm_hint), style = MaterialTheme.typography.bodySmall)
            Row {
                Button(
                    onClick = actions.onConfirmDeletes,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.detail_delete_them)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = actions.onRestoreInstead,
                ) { Text(stringResource(R.string.detail_restore_them)) }
            }
        }
    }
}

@Composable
internal fun OptionsSection(
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    Column(sectionPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(
            stringResource(R.string.detail_sync_enabled),
            stringResource(R.string.detail_sync_enabled_body),
            state.enabled,
            actions.onEnabled,
        )
        Text(stringResource(R.string.detail_direction), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val all = SyncDirection.entries
            all.forEachIndexed { i, direction ->
                SegmentedButton(
                    selected = state.settings.direction == direction,
                    onClick = { actions.onDirection(direction) },
                    shape = SegmentedButtonDefaults.itemShape(i, all.size),
                ) { Text(direction.label(), maxLines = 1) }
            }
        }
        if (AddPairRules.mirrorAllowed(state.settings.direction)) {
            SwitchRow(
                stringResource(R.string.add_mirror),
                stringResource(R.string.add_mirror_body),
                state.settings.mirrorDeletions,
                actions.onMirror,
            )
        }
        PolicyMenu(state.settings.conflictPolicy, actions.onPolicy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyMenu(
    policy: ConflictPolicy,
    onPolicy: (ConflictPolicy) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = policy.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.add_conflicts)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun RecentSection(
    state: PairDetailUiState,
    actions: PairDetailActions,
) {
    Column {
        Text(
            stringResource(R.string.detail_recent),
            style = MaterialTheme.typography.titleSmall,
            modifier = sectionPadding,
        )
        if (state.recent.isEmpty()) {
            Text(
                stringResource(R.string.detail_no_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = sectionPadding.padding(top = 8.dp),
            )
        }
        state.recent.forEach { ActivityRowItem(it, showPair = false) }
        TextButton(onClick = actions.onOpenActivity, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.detail_see_all))
        }
    }
}

@Composable
internal fun RemoveSection(
    name: String,
    actions: PairDetailActions,
    modifier: Modifier = Modifier,
) {
    var asking by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = { asking = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.detail_remove)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.detail_remove_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.detail_remove_body))
                    Row(
                        Modifier.toggleable(trash, role = Role.Checkbox) { trash = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = trash, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
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
