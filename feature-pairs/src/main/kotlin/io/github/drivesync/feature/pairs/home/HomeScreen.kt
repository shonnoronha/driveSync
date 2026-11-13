package io.github.drivesync.feature.pairs.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.StatusChip
import io.github.drivesync.core.ui.components.StatusTone
import io.github.drivesync.feature.pairs.R

/** Home (FR-M1): every pair with its status, last sync and a sync-now action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { HomeTopBar(state.conflictCount, actions) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAddPair,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_folder)) },
            )
        },
    ) { padding ->
        // The pull gesture only starts a sync; progress shows as a thin bar, not a spinner over the list.
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = actions.onSyncAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (state.pairs.any { it.state == PairState.SYNCING }) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                state.pairs.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.Folder,
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.pairs, key = { it.id }) { pair -> PairCard(pair, state.now, actions) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    conflictCount: Int,
    actions: HomeActions,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.home_title)) },
        actions = {
            IconButton(onClick = actions.onOpenConflicts) {
                BadgedBox(badge = { if (conflictCount > 0) Badge { Text("$conflictCount") } }) {
                    Icon(Icons.Outlined.Difference, stringResource(R.string.home_conflicts))
                }
            }
            IconButton(onClick = actions.onOpenActivity) {
                Icon(Icons.Outlined.History, stringResource(R.string.home_activity))
            }
            IconButton(onClick = actions.onOpenSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.home_settings))
            }
        },
    )
}

@Composable
private fun PairCard(
    pair: PairItem,
    now: Long,
    actions: HomeActions,
) {
    val attention = pair.state == PairState.NEEDS_ATTENTION || pair.state == PairState.CONFIRM_DELETES
    val colors =
        if (attention) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
        } else {
            CardDefaults.cardColors()
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().clickable { actions.onOpenPair(pair.id) }) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    pair.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(pair.state.label(), pair.state.tone(), Modifier.padding(end = 8.dp))
            }
            PairPaths(pair)
            if (attention && pair.detail != null) {
                Text(pair.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            PairFooter(pair, now, attention, actions)
        }
    }
}

@Composable
private fun PairPaths(pair: PairItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            pair.direction.icon(),
            contentDescription = pair.direction.label(),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        val phone =
            stringResource(
                if (pair.onInternalStorage) R.string.pair_phone_path else R.string.pair_phone_sd_path,
                pair.phonePath,
            )
        Text(
            "$phone  ·  ${stringResource(R.string.pair_drive_path, pair.name)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PairFooter(
    pair: PairItem,
    now: Long,
    attention: Boolean,
    actions: HomeActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp)) {
        Text(
            lastSynced(pair.lastSyncAt, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (attention) {
            FilledTonalButton(onClick = { actions.onOpenPair(pair.id) }) {
                Text(stringResource(R.string.pair_fix))
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (pair.state != PairState.PAUSED) {
            TextButton(onClick = { actions.onSyncPair(pair.id) }, enabled = pair.state != PairState.SYNCING) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pair_sync_now))
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

internal fun SyncDirection.icon() =
    when (this) {
        SyncDirection.TWO_WAY -> Icons.Outlined.SyncAlt
        SyncDirection.UPLOAD_ONLY -> Icons.Outlined.CloudUpload
        SyncDirection.DOWNLOAD_ONLY -> Icons.Outlined.CloudDownload
    }
