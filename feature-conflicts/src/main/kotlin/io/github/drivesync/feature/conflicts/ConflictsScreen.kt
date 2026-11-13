package io.github.drivesync.feature.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.ui.components.EmptyState

/** Conflicts (PRD 8.5): pending ones need a choice; the rest show what the last sync did. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    state: ConflictsUiState,
    actions: ConflictsActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.conflicts_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.pending.isEmpty() && state.resolved.isEmpty() -> {
                    EmptyState(
                        Icons.Outlined.CheckCircle,
                        stringResource(R.string.conflicts_empty_title),
                        stringResource(R.string.conflicts_empty_body),
                        Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    ConflictList(state, actions)
                }
            }
        }
    }
}

@Composable
private fun ConflictList(
    state: ConflictsUiState,
    actions: ConflictsActions,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.pending.isNotEmpty()) {
            item { Header(stringResource(R.string.conflicts_pending)) }
            items(state.pending, key = { "p:${it.pairId}:${it.path}" }) { PendingCard(it, actions) }
        }
        if (state.resolved.isNotEmpty()) {
            item { Header(stringResource(R.string.conflicts_resolved)) }
            items(state.resolved, key = { "r:${it.pairId}:${it.path}" }) { ResolvedCard(it) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PendingCard(
    item: ConflictItem,
    actions: ConflictsActions,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FileTitle(item)
            Text(item.kind.label(), style = MaterialTheme.typography.bodyMedium)
            if (item.choices.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    item.choices.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = item.chosen == choice,
                            onClick = { actions.onChoose(item, choice) },
                            shape = SegmentedButtonDefaults.itemShape(index, item.choices.size),
                        ) { Text(choice.label(item.choices.size == 1), maxLines = 1) }
                    }
                }
            }
            item.chosen?.let {
                Text(
                    stringResource(R.string.conflicts_chosen, it.label(item.choices.size == 1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ResolvedCard(item: ConflictItem) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FileTitle(item)
            Text(
                item.kind.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(item.resolutionText(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FileTitle(item: ConflictItem) {
    Row {
        Text(
            item.path,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        item.pairName,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConflictPolicy.label(single: Boolean): String =
    stringResource(
        when (this) {
            ConflictPolicy.LOCAL_WINS -> R.string.conflicts_keep_phone
            ConflictPolicy.REMOTE_WINS -> R.string.conflicts_keep_drive
            else -> if (single) R.string.conflicts_keep_file else R.string.conflicts_keep_both
        },
    )

@Composable
private fun ConflictKind.label(): String =
    stringResource(
        when (this) {
            ConflictKind.BOTH_MODIFIED -> R.string.kind_both_modified
            ConflictKind.BOTH_NEW -> R.string.kind_both_new
            ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED -> R.string.kind_local_modified_remote_deleted
            ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED -> R.string.kind_remote_modified_local_deleted
            ConflictKind.DESTINATION_MODIFIED -> R.string.kind_destination_modified
            ConflictKind.TYPE_MISMATCH -> R.string.kind_type_mismatch
            ConflictKind.DUPLICATE_REMOTE_NAME -> R.string.kind_duplicate
        },
    )

@Composable
private fun ConflictItem.resolutionText(): String =
    when (resolution) {
        ConflictResolution.KEPT_BOTH -> {
            conflictCopy?.let { stringResource(R.string.resolution_kept_both, it) }
                ?: stringResource(R.string.resolution_kept_both_plain)
        }

        ConflictResolution.LOCAL_WON -> {
            stringResource(R.string.resolution_local_won)
        }

        ConflictResolution.REMOTE_WON -> {
            stringResource(R.string.resolution_remote_won)
        }

        ConflictResolution.KEPT_MODIFIED -> {
            stringResource(R.string.resolution_kept_modified)
        }

        ConflictResolution.PENDING -> {
            ""
        }
    }
