package io.github.drivesync.feature.pairs.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.IconTile
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.RootTopBar
import io.github.drivesync.core.ui.components.StatusChip
import io.github.drivesync.core.ui.components.StatusTone
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.pairs.R

/** Home (FR-M1): every pair with its status, last sync and a sync-now action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            RootTopBar(stringResource(R.string.home_title), scroll) {
                IconButton(onClick = actions.onSyncAll) { SymIcon(Sym.Sync, stringResource(R.string.home_sync_all)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAddPair,
                icon = { SymIcon(Sym.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_folder)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = actions.onSyncAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.loading -> {
                    LoadingState()
                }

                state.pairs.isEmpty() -> {
                    EmptyState(
                        icon = Sym.CreateNewFolder,
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding =
                            PaddingValues(
                                start = Tokens.pageInset,
                                end = Tokens.pageInset,
                                top = 4.dp,
                                bottom = 104.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Tokens.gapMd),
                    ) {
                        items(state.pairs, key = { it.id }) { pair -> PairCard(pair, state.now, actions) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairCard(
    pair: PairItem,
    now: Long,
    actions: HomeActions,
) {
    DsCard(onClick = { actions.onOpenPair(pair.id) }) {
        Column(Modifier.padding(Tokens.gapLg), verticalArrangement = Arrangement.spacedBy(Tokens.gapMd)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (container, content) = pair.state.tileColors()
                IconTile(pair.direction.icon(), container = container, content = content)
                Spacer(Modifier.width(Tokens.gapMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        pair.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PairPaths(pair)
                }
                Spacer(Modifier.width(Tokens.gapSm))
                StatusChip(pair.state.label(), pair.state.tone())
            }
            if (pair.state == PairState.SYNCING) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            if (pair.attention && pair.detail != null) {
                Text(pair.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            PairFooter(pair, now, actions)
        }
    }
}

private val PairItem.attention get() = state == PairState.NEEDS_ATTENTION || state == PairState.CONFIRM_DELETES

@Composable
private fun PairPaths(pair: PairItem) {
    val phone =
        stringResource(
            if (pair.onInternalStorage) R.string.pair_phone_path else R.string.pair_phone_sd_path,
            pair.phonePath,
        )
    Text(
        "$phone  →  ${stringResource(R.string.pair_drive_path, pair.name)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PairFooter(
    pair: PairItem,
    now: Long,
    actions: HomeActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(
            lastSynced(pair.lastSyncAt, now),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        when {
            pair.attention -> {
                Button(onClick = { actions.onOpenPair(pair.id) }) {
                    Text(stringResource(R.string.pair_fix))
                    Spacer(Modifier.width(Tokens.gapSm))
                    SymIcon(Sym.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            pair.state != PairState.PAUSED -> {
                FilledTonalButton(
                    onClick = { actions.onSyncPair(pair.id) },
                    enabled = pair.state != PairState.SYNCING,
                ) {
                    SymIcon(Sym.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Tokens.gapSm))
                    Text(stringResource(R.string.pair_sync_now))
                }
            }
        }
    }
}

@Composable
private fun lastSynced(
    at: Long?,
    now: Long,
): String =
    if (at == null) {
        stringResource(R.string.pair_never_synced)
    } else {
        val ago = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
        stringResource(R.string.pair_last_synced, ago)
    }

@Composable
private fun PairState.tileColors(): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (this) {
        PairState.NEEDS_ATTENTION -> c.errorContainer to c.onErrorContainer
        PairState.CONFIRM_DELETES, PairState.ERRORS -> c.tertiaryContainer to c.onTertiaryContainer
        PairState.PAUSED -> c.surfaceContainerHighest to c.onSurfaceVariant
        else -> c.primaryContainer to c.onPrimaryContainer
    }
}

@Composable
internal fun PairState.label(): String =
    stringResource(
        when (this) {
            PairState.UP_TO_DATE -> R.string.status_up_to_date
            PairState.SYNCING -> R.string.status_syncing
            PairState.NEEDS_ATTENTION -> R.string.status_needs_attention
            PairState.CONFIRM_DELETES -> R.string.status_confirm_deletes
            PairState.ERRORS -> R.string.status_errors
            PairState.PAUSED -> R.string.status_paused
        },
    )

internal fun PairState.tone(): StatusTone =
    when (this) {
        PairState.UP_TO_DATE -> StatusTone.OK
        PairState.SYNCING -> StatusTone.WORKING
        PairState.NEEDS_ATTENTION -> StatusTone.ERROR
        PairState.CONFIRM_DELETES, PairState.ERRORS -> StatusTone.WARNING
        PairState.PAUSED -> StatusTone.NEUTRAL
    }

@Composable
internal fun SyncDirection.label(): String =
    stringResource(
        when (this) {
            SyncDirection.TWO_WAY -> R.string.direction_two_way
            SyncDirection.UPLOAD_ONLY -> R.string.direction_upload_only
            SyncDirection.DOWNLOAD_ONLY -> R.string.direction_download_only
        },
    )

internal fun SyncDirection.icon(): Int =
    when (this) {
        SyncDirection.TWO_WAY -> Sym.SyncAlt
        SyncDirection.UPLOAD_ONLY -> Sym.CloudUpload
        SyncDirection.DOWNLOAD_ONLY -> Sym.CloudDownload
    }
