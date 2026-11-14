package io.github.drivesync.feature.pairs.files

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.StatusChip
import io.github.drivesync.core.ui.components.StatusTone
import io.github.drivesync.core.ui.components.SubTopBar
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.connectedShape
import io.github.drivesync.core.ui.components.groupColor
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.pairs.R
import io.github.drivesync.feature.pairs.home.lastSynced

@Composable
fun FilesRoute(
    onBack: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.folder != null) { viewModel.up() }
    // Phone files may have changed while the app was in the background.
    LifecycleResumeEffect(viewModel) {
        viewModel.rescan()
        onPauseOrDispose { }
    }
    FilesScreen(
        state = state,
        actions =
            FilesActions(
                onBack = { if (!viewModel.up()) onBack() },
                onOpen = viewModel::open,
                onOnlyNotSynced = viewModel::setOnlyNotSynced,
                onSyncNow = viewModel::syncNow,
                onRescan = viewModel::rescan,
                onOpenConflicts = onOpenConflicts,
            ),
    )
}

data class FilesActions(
    val onBack: () -> Unit = {},
    val onOpen: (FileRow) -> Unit = {},
    val onOnlyNotSynced: (Boolean) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onOpenConflicts: () -> Unit = {},
)

/** Which files of a pair are synced (PRD 4.3: last-synced state and per-file errors). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FilesUiState,
    actions: FilesActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SubTopBar(
                state.folder?.name ?: stringResource(R.string.files_title),
                Sym.ArrowBack,
                stringResource(R.string.detail_back),
                actions.onBack,
                scroll,
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val rows = state.rows
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = Tokens.gapXl)) {
            item(key = "summary") { Summary(state, actions) }
            item(key = "where") { Where(state) }
            itemsIndexed(rows, key = { _, row -> row.path.value }) { index, row ->
                Surface(
                    color = groupColor(),
                    shape = connectedShape(first = index == 0, last = index == rows.lastIndex),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Tokens.pageInset)
                            .padding(bottom = Tokens.connectedGap),
                ) { FileRowItem(row, actions) }
            }
            if (rows.isEmpty()) item(key = "empty") { Empty(state, actions) }
        }
    }
}

@Composable
private fun Summary(
    state: FilesUiState,
    actions: FilesActions,
) {
    val index = state.index
    DsCard(Modifier.padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm)) {
        Column(Modifier.padding(Tokens.rowPaddingH), verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            when {
                state.unavailable != null -> {
                    StatusChip(stringResource(R.string.files_unreadable), StatusTone.ERROR)
                }

                state.syncing -> {
                    StatusChip(stringResource(R.string.files_syncing), StatusTone.WORKING)
                }

                index.notSynced == 0 -> {
                    StatusChip(
                        pluralStringResource(R.plurals.files_all_synced, index.totalFiles, index.totalFiles),
                        StatusTone.OK,
                    )
                }

                else -> {
                    StatusChip(
                        pluralStringResource(
                            R.plurals.files_not_synced,
                            index.totalFiles,
                            index.notSynced,
                            index.totalFiles,
                        ),
                        StatusTone.WARNING,
                    )
                }
            }
            Text(
                state.unavailable ?: stringResource(R.string.files_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(lastSynced(state.lastSyncAt, state.now), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapSm),
            ) {
                FilledTonalButton(onClick = actions.onSyncNow, enabled = !state.syncing) {
                    Text(stringResource(R.string.pair_sync_now))
                }
                FilterChip(
                    selected = state.onlyNotSynced,
                    onClick = { actions.onOnlyNotSynced(!state.onlyNotSynced) },
                    label = { Text(stringResource(R.string.files_only_not_synced)) },
                )
            }
        }
    }
}

/** "DriveSyncTest / Photos / 2026", so the user knows where in the folder they are. */
@Composable
private fun Where(state: FilesUiState) {
    val path = listOf(state.pairName) + state.folder?.segments.orEmpty()
    Text(
        path.joinToString(" / "),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.padding(
                start = Tokens.pageInset + Tokens.gapLg,
                end = Tokens.pageInset,
                top = Tokens.gapLg,
                bottom = Tokens.gapSm,
            ),
    )
}

@Composable
private fun Empty(
    state: FilesUiState,
    actions: FilesActions,
) {
    if (state.unavailable != null) return
    val all = state.onlyNotSynced && state.index.rowsIn(state.folder).isNotEmpty()
    EmptyState(
        icon = if (all) Sym.CloudDone else Sym.FolderOpen,
        title = stringResource(if (all) R.string.files_empty_all_synced else R.string.files_empty),
        body = stringResource(if (all) R.string.files_empty_all_synced_body else R.string.files_empty_body),
        action =
            if (all) {
                {
                    FilledTonalButton(
                        onClick = { actions.onOnlyNotSynced(false) },
                    ) { Text(stringResource(R.string.files_show_all)) }
                }
            } else {
                { FilledTonalButton(onClick = actions.onRescan) { Text(stringResource(R.string.files_check_again)) } }
            },
    )
}

@Composable
private fun FileRowItem(
    row: FileRow,
    actions: FilesActions,
) {
    if (row.isFolder) {
        SettingsRow(
            title = row.name,
            subtitle = folderSubtitle(row),
            icon = Sym.Folder,
            iconTint = if (row.notSynced > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = { actions.onOpen(row) },
        )
    } else {
        SettingsRow(
            title = row.name,
            subtitle = fileSubtitle(row),
            icon = row.status.icon(),
            iconTint = row.status.tint(),
            onClick = if (row.status == FileStatus.CONFLICT) actions.onOpenConflicts else null,
        )
    }
}

@Composable
private fun folderSubtitle(row: FileRow): String {
    val files = pluralStringResource(R.plurals.files_count, row.files, row.files)
    return when {
        row.files == 0 -> stringResource(R.string.files_folder_empty)
        row.notSynced == 0 -> stringResource(R.string.files_folder_synced, files)
        else -> stringResource(R.string.files_folder_not_synced, files, row.notSynced)
    }
}

@Composable
private fun fileSubtitle(row: FileRow): String {
    val status =
        when (row.status) {
            FileStatus.SYNCED -> {
                stringResource(R.string.files_status_synced)
            }

            FileStatus.NEW_ON_PHONE -> {
                stringResource(R.string.files_status_new)
            }

            FileStatus.CHANGED_ON_PHONE -> {
                stringResource(R.string.files_status_changed)
            }

            FileStatus.REMOVED_FROM_PHONE -> {
                stringResource(R.string.files_status_removed)
            }

            FileStatus.CONFLICT -> {
                stringResource(R.string.files_status_conflict)
            }

            FileStatus.FAILED -> {
                row.detail?.let { stringResource(R.string.files_status_failed_because, it) }
                    ?: stringResource(R.string.files_status_failed)
            }

            FileStatus.SKIPPED -> {
                stringResource(R.string.files_status_skipped)
            }
        }
    if (row.size <= 0 || row.status == FileStatus.SKIPPED) return status
    return status + " · " + Formatter.formatShortFileSize(LocalContext.current, row.size)
}

private fun FileStatus.icon(): Int =
    when (this) {
        FileStatus.SYNCED -> Sym.CloudDone
        FileStatus.NEW_ON_PHONE, FileStatus.CHANGED_ON_PHONE -> Sym.CloudUpload
        FileStatus.REMOVED_FROM_PHONE -> Sym.Delete
        FileStatus.CONFLICT -> Sym.Difference
        FileStatus.FAILED -> Sym.Error
        FileStatus.SKIPPED -> Sym.Block
    }

@Composable
private fun FileStatus.tint(): Color {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        FileStatus.SYNCED -> colors.primary
        FileStatus.NEW_ON_PHONE, FileStatus.CHANGED_ON_PHONE -> colors.tertiary
        FileStatus.REMOVED_FROM_PHONE, FileStatus.SKIPPED -> colors.onSurfaceVariant
        FileStatus.CONFLICT, FileStatus.FAILED -> colors.error
    }
}
