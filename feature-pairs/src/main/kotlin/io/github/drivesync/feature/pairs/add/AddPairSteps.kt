package io.github.drivesync.feature.pairs.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.feature.pairs.R
import io.github.drivesync.feature.pairs.home.icon
import io.github.drivesync.feature.pairs.home.label

@Composable
internal fun FolderStep(
    state: AddPairUiState,
    actions: AddPairActions,
) {
    StepTitle(stringResource(R.string.add_folder_title), stringResource(R.string.add_folder_body))
    if (state.treeUri == null) {
        OutlinedCard(onClick = actions.onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.add_folder_pick), style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                val prefix = if (state.onInternalStorage) "Phone/" else "SD card/"
                Text(prefix + state.folderPath.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton(onClick = actions.onPickFolder) { Text(stringResource(R.string.add_folder_change)) }
    }
    state.problems.forEach { ProblemCard(it) }
}

@Composable
private fun ProblemCard(problem: FolderProblem) {
    val text =
        when (problem) {
            is FolderProblem.Overlaps -> stringResource(R.string.add_problem_overlap, problem.pairName)
            FolderProblem.UnsupportedProvider -> stringResource(R.string.add_problem_provider)
            is FolderProblem.GrantLimitNear -> stringResource(R.string.add_problem_grants, problem.used)
        }
    val colors =
        if (problem.blocking) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(if (problem.blocking) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun NameStep(
    state: AddPairUiState,
    actions: AddPairActions,
) {
    StepTitle(stringResource(R.string.add_name_title))
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.add_name_label)) },
        supportingText = { Text(stringResource(R.string.add_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ChoiceRow(stringResource(R.string.add_target_new), null, state.target == DriveTarget.New) {
        actions.onTarget(DriveTarget.New)
    }
    if (state.linkableFolders.isNotEmpty()) {
        Text(stringResource(R.string.add_target_existing), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.add_target_existing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.linkableFolders.forEach { folder ->
            val selected = (state.target as? DriveTarget.Existing)?.folder == folder
            ChoiceRow("Drive Sync/${folder.name}", null, selected) { actions.onTarget(DriveTarget.Existing(folder)) }
        }
    }
}

@Composable
internal fun OptionsStep(
    state: AddPairUiState,
    actions: AddPairActions,
) {
    StepTitle(stringResource(R.string.add_options_title))
    SyncDirection.entries.forEach { direction ->
        DirectionCard(direction, selected = state.direction == direction) { actions.onDirection(direction) }
    }
    if (AddPairRules.mirrorAllowed(state.direction)) {
        Row(
            Modifier.fillMaxWidth().toggleable(
                state.mirrorDeletions,
                role = Role.Switch,
                onValueChange = actions.onMirror,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.add_mirror), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.add_mirror_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.mirrorDeletions, onCheckedChange = null)
        }
    }
    Text(stringResource(R.string.add_conflicts), style = MaterialTheme.typography.titleSmall)
    ConflictPolicy.entries.forEach { policy ->
        ChoiceRow(policy.label(), null, state.conflictPolicy == policy) { actions.onPolicy(policy) }
    }
}

@Composable
private fun DirectionCard(
    direction: SyncDirection,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors =
        if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.outlinedCardColors()
        }
    OutlinedCard(
        colors = colors,
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(direction.icon(), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(direction.label(), style = MaterialTheme.typography.titleMedium)
                Text(direction.body(), style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
internal fun ReviewStep(state: AddPairUiState) {
    StepTitle(stringResource(R.string.add_review_title))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.name, style = MaterialTheme.typography.titleLarge)
            val prefix = if (state.onInternalStorage) "Phone/" else "SD card/"
            Text(prefix + state.folderPath.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            val driveName = (state.target as? DriveTarget.Existing)?.folder?.name ?: state.name
            Text("Drive Sync/$driveName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${state.direction.label()} · ${state.conflictPolicy.label()}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val preview = state.preview
    if (preview == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.add_review_counting))
        }
    } else {
        Text(stringResource(R.string.add_review_phone, preview.phoneFiles), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (preview.driveFiles == null) {
                stringResource(R.string.add_review_drive_new)
            } else {
                stringResource(R.string.add_review_drive_existing, preview.driveFiles)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Text(
        stringResource(R.string.add_review_first_sync),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ChoiceRow(
    title: String,
    body: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected,
                role = Role.RadioButton,
                onClick = onSelect,
            ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SyncDirection.body(): String =
    stringResource(
        when (this) {
            SyncDirection.TWO_WAY -> R.string.add_direction_two_way_body
            SyncDirection.UPLOAD_ONLY -> R.string.add_direction_upload_body
            SyncDirection.DOWNLOAD_ONLY -> R.string.add_direction_download_body
        },
    )

@Composable
internal fun ConflictPolicy.label(): String =
    stringResource(
        when (this) {
            ConflictPolicy.KEEP_BOTH -> R.string.policy_keep_both
            ConflictPolicy.NEWEST_WINS -> R.string.policy_newest
            ConflictPolicy.LOCAL_WINS -> R.string.policy_local
            ConflictPolicy.REMOTE_WINS -> R.string.policy_remote
            ConflictPolicy.ASK -> R.string.policy_ask
        },
    )
