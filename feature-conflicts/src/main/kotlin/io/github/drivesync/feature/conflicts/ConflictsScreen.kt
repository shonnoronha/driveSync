package io.github.drivesync.feature.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.ui.components.ConnectedToggleGroup
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.IconTile
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.RootTopBar
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.theme.Tokens

/** Conflicts (PRD 8.5): pending ones need a choice; the rest show what the last sync did. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    state: ConflictsUiState,
    actions: ConflictsActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { RootTopBar(stringResource(R.string.conflicts_title), scroll) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    LoadingState()
                }

                state.pending.isEmpty() && state.resolved.isEmpty() -> {
                    EmptyState(
                        Sym.CheckCircle,
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
    LazyColumn(
        contentPadding = PaddingValues(bottom = Tokens.gapXl),
        verticalArrangement = Arrangement.spacedBy(Tokens.gapMd),
    ) {
        if (state.pending.isNotEmpty()) {
            item { SettingsSectionHeader(stringResource(R.string.conflicts_pending)) }
            items(state.pending, key = { "p:${it.pairId}:${it.path}" }) { PendingCard(it, actions) }
        }
        if (state.resolved.isNotEmpty()) {
            item {
                SettingsSectionHeader(stringResource(R.string.conflicts_resolved))
                SettingsGroup {
                    state.resolved.forEach { conflict ->
                        item {
                            SettingsRow(
                                title = conflict.path,
                                subtitle = "${conflict.pairName} · ${conflict.resolutionText()}",
                                icon = Sym.CheckCircle,
                                iconTint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(
    item: ConflictItem,
    actions: ConflictsActions,
) {
    DsCard(Modifier.padding(horizontal = Tokens.pageInset)) {
        Column(Modifier.padding(Tokens.rowPaddingH), verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val scheme = MaterialTheme.colorScheme
                IconTile(Sym.Difference, container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
                Spacer(Modifier.width(Tokens.gapMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.path,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.pairName, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            }
            Text(item.kind.label(), style = MaterialTheme.typography.bodyMedium)
            if (item.choices.isNotEmpty()) {
                ConnectedToggleGroup(item.choices, item.chosen, { actions.onChoose(item, it) }) {
                    Text(it.label(item.choices.size == 1), maxLines = 1)
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
