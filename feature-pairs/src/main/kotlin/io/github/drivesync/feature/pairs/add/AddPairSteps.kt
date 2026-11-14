package io.github.drivesync.feature.pairs.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.DsTextField
import io.github.drivesync.core.ui.components.IconTile
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.pairs.R
import io.github.drivesync.feature.pairs.home.icon
import io.github.drivesync.feature.pairs.home.label

private val inset = Modifier.padding(horizontal = Tokens.pageInset)

@Composable
internal fun FolderStep(
    state: AddPairUiState,
    actions: AddPairActions,
) {
    StepTitle(stringResource(R.string.add_folder_title), stringResource(R.string.add_folder_body))
    val picked = state.treeUri != null
    DsCard(inset, onClick = actions.onPickFolder) {
        Row(Modifier.padding(Tokens.rowPaddingH), verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (picked) Sym.FolderFilled else Sym.CreateNewFolder)
            Spacer(Modifier.width(Tokens.gapLg))
            Column(Modifier.weight(1f)) {
                val title =
                    if (picked) {
                        (if (state.onInternalStorage) "" else "SD card/") + state.folderPath.orEmpty()
                    } else {
                        stringResource(
                            R.string.add_folder_pick,
                        )
                    }
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (picked) {
                    Text(
                        stringResource(R.string.add_folder_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
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
    val scheme = MaterialTheme.colorScheme
    DsCard(inset, container = if (problem.blocking) scheme.errorContainer else scheme.tertiaryContainer) {
        Row(Modifier.padding(Tokens.rowPaddingH)) {
            SymIcon(if (problem.blocking) Sym.Error else Sym.Info, contentDescription = null)
            Spacer(Modifier.width(Tokens.gapMd))
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
    DsTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = stringResource(R.string.add_name_label),
        supportingText = stringResource(R.string.add_name_hint),
        modifier = inset,
    )
    SettingsSectionHeader(stringResource(R.string.add_drive_folder))
    SettingsGroup {
        item {
            RadioRow(stringResource(R.string.add_target_new), null, state.target == DriveTarget.New) {
                actions.onTarget(DriveTarget.New)
            }
        }
        state.linkableFolders.forEach { folder ->
            item {
                val selected = (state.target as? DriveTarget.Existing)?.folder == folder
                RadioRow("Drive Sync/${folder.name}", stringResource(R.string.add_target_existing), selected) {
                    actions.onTarget(DriveTarget.Existing(folder))
                }
            }
        }
    }
    if (state.linkableFolders.isNotEmpty()) {
        Text(
            stringResource(
                if (state.targetSuggested) R.string.add_target_suggested else R.string.add_target_existing_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Tokens.pageInset + Tokens.gapLg),
        )
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
        SettingsGroup {
            item {
                SettingsSwitchRow(
                    stringResource(R.string.add_mirror),
                    state.mirrorDeletions,
                    actions.onMirror,
                    subtitle = stringResource(R.string.add_mirror_body),
                    icon = Sym.Delete,
                )
            }
        }
    }
    SettingsSectionHeader(stringResource(R.string.add_conflicts))
    SettingsGroup {
        ConflictPolicy.entries.forEach { policy ->
            item { RadioRow(policy.label(), null, state.conflictPolicy == policy) { actions.onPolicy(policy) } }
        }
    }
}

@Composable
private fun DirectionCard(
    direction: SyncDirection,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    DsCard(
        inset.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        container = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(Tokens.gapLg), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                direction.icon(),
                container = if (selected) scheme.primary else scheme.surfaceContainerHighest,
                content = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Tokens.gapLg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(direction.label(), style = MaterialTheme.typography.titleMedium)
                Text(direction.body(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReviewStep(state: AddPairUiState) {
    StepTitle(stringResource(R.string.add_review_title), stringResource(R.string.add_review_first_sync))
    val driveName = (state.target as? DriveTarget.Existing)?.folder?.name ?: state.name
    SettingsSectionHeader(state.name)
    SettingsGroup {
        item {
            SettingsRow(
                title = (if (state.onInternalStorage) "" else "SD card/") + state.folderPath.orEmpty(),
                subtitle = state.preview?.let { stringResource(R.string.add_review_phone, it.phoneFiles) },
                icon = Sym.Smartphone,
            )
        }
        item {
            val preview = state.preview
            SettingsRow(
                title = "Drive Sync/$driveName",
                subtitle =
                    when {
                        preview == null -> null
                        preview.driveFiles == null -> stringResource(R.string.add_review_drive_new)
                        else -> stringResource(R.string.add_review_drive_existing, preview.driveFiles)
                    },
                icon = Sym.CloudSync,
            )
        }
        item {
            SettingsRow(
                title = state.direction.label(),
                subtitle = state.conflictPolicy.label(),
                icon = state.direction.icon(),
            )
        }
    }
    if (state.preview == null) {
        Row(inset.padding(horizontal = Tokens.gapLg), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(40.dp))
            Spacer(Modifier.width(Tokens.gapSm))
            Text(stringResource(R.string.add_review_counting))
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = inset) }
}

@Composable
private fun RadioRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
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
