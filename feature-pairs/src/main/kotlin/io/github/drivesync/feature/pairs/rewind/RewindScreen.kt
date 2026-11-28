package io.github.drivesync.feature.pairs.rewind

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.drivesync.core.sync.plan.RewindChange
import io.github.drivesync.core.sync.plan.RewindChangeKind
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.ui.components.DsCard
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.SubTopBar
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.components.connectedShape
import io.github.drivesync.core.ui.components.groupColor
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.core.work.RewindResult
import io.github.drivesync.feature.pairs.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Rewind (time travel for one folder): a slider over its sync history, what going back changes, and confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewindScreen(
    state: RewindUiState,
    onBack: () -> Unit,
    onSelect: (Long) -> Unit,
    onRewind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var confirming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SubTopBar(
                stringResource(R.string.rewind_title, state.pairName),
                Sym.ArrowBack,
                stringResource(R.string.detail_back),
                onBack,
                scroll,
            )
        },
        bottomBar = {
            if (state.points.size > 1) {
                Button(
                    onClick = { confirming = true },
                    enabled = state.canRewind,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm),
                ) { Text(stringResource(R.string.rewind_go)) }
            }
        },
    ) { padding ->
        when {
            state.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            state.points.size < 2 -> {
                EmptyState(
                    Sym.History,
                    stringResource(R.string.rewind_empty_title),
                    stringResource(R.string.rewind_empty_body),
                    Modifier.padding(padding),
                )
            }

            else -> {
                Timeline(state, onSelect, Modifier.padding(padding))
            }
        }
    }
    if (confirming) {
        ConfirmDialog(state, onDismiss = { confirming = false }) {
            confirming = false
            onRewind()
        }
    }
}

@Composable
private fun Timeline(
    state: RewindUiState,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val changes = state.changes
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = Tokens.gapXl)) {
        item(key = "picker") { Picker(state, onSelect) }
        item(key = "status") { Status(state) }
        itemsIndexed(changes, key = { _, c -> "${c.kind}:${c.path.value}" }) { index, change ->
            Surface(
                color = groupColor(),
                shape = connectedShape(first = index == 0, last = index == changes.lastIndex),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.pageInset)
                        .padding(bottom = Tokens.connectedGap),
            ) { ChangeRow(change) }
        }
    }
}

@Composable
private fun Picker(
    state: RewindUiState,
    onSelect: (Long) -> Unit,
) {
    val points = state.points
    val last = points.lastIndex
    DsCard(Modifier.padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm)) {
        Column(Modifier.padding(Tokens.rowPaddingH), verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
            Text(stringResource(R.string.rewind_back_to), style = MaterialTheme.typography.labelLarge)
            Text(pointLabel(state.selected, points), style = MaterialTheme.typography.headlineSmall)
            Slider(
                value = state.selected.toFloat(),
                onValueChange = { onSelect(points[it.roundToInt().coerceIn(0, last)].time) },
                valueRange = 0f..last.toFloat(),
                steps = (last - 1).coerceAtLeast(0),
                enabled = !state.running,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pointLabel(0, points), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.rewind_now), style = MaterialTheme.typography.bodySmall)
            }
            Summary(state)
        }
    }
}

@Composable
private fun Summary(state: RewindUiState) {
    val style = MaterialTheme.typography.bodyMedium
    when {
        state.refusal == RewindRefusal.EMPTY_POINT -> {
            Text(stringResource(R.string.rewind_refused_empty), style = style)
        }

        state.isNow || state.changes.isEmpty() -> {
            Text(stringResource(R.string.rewind_no_changes), style = style)
        }

        else -> {
            val back = state.files(RewindChangeKind.BRING_BACK)
            val restore = state.files(RewindChangeKind.RESTORE)
            val trash = state.files(RewindChangeKind.TO_TRASH)
            if (restore > 0) Text(pluralStringResource(R.plurals.rewind_restore, restore, restore), style = style)
            if (back > 0) Text(pluralStringResource(R.plurals.rewind_back, back, back), style = style)
            if (trash > 0) Text(pluralStringResource(R.plurals.rewind_trash, trash, trash), style = style)
        }
    }
}

@Composable
private fun Status(state: RewindUiState) {
    val result = state.result
    when {
        state.running -> {
            Column(
                Modifier.padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapXs),
            ) {
                Text(stringResource(R.string.rewind_running), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        result != null -> {
            Text(
                resultText(result),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Tokens.pageInset, vertical = Tokens.gapSm),
            )
        }
    }
}

@Composable
private fun ChangeRow(change: RewindChange) {
    val (icon, label) =
        when (change.kind) {
            RewindChangeKind.RESTORE -> Sym.History to R.string.rewind_kind_restore
            RewindChangeKind.BRING_BACK -> Sym.RestoreFromTrash to R.string.rewind_kind_back
            RewindChangeKind.TO_TRASH -> Sym.Delete to R.string.rewind_kind_trash
        }
    Row(
        Modifier.padding(horizontal = Tokens.rowPaddingH, vertical = Tokens.gapSm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymIcon(if (change.isFolder) Sym.Folder else icon, null)
        Column(Modifier.weight(1f)) {
            Text(change.path.value, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    state: RewindUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SymIcon(Sym.History, null) },
        title = { Text(stringResource(R.string.rewind_confirm_title, state.pairName)) },
        text = { Text(stringResource(R.string.rewind_confirm_body, pointLabel(state.selected, state.points))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.rewind_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) } },
    )
}

@Composable
private fun pointLabel(
    index: Int,
    points: List<RewindPoint>,
): String {
    if (index == points.lastIndex) return stringResource(R.string.rewind_now)
    val time = Instant.ofEpochMilli(points[index].time).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now()) {
        stringResource(R.string.rewind_today, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
    } else {
        time.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}

@Composable
private fun resultText(result: RewindResult): String =
    when (result) {
        is RewindResult.Done -> {
            buildList {
                add(pluralStringResource(R.plurals.rewind_done, result.changed, result.changed))
                if (result.skipped >
                    0
                ) {
                    add(pluralStringResource(R.plurals.rewind_skipped, result.skipped, result.skipped))
                }
                if (result.failed > 0) add(pluralStringResource(R.plurals.rewind_failed, result.failed, result.failed))
            }.joinToString(" ")
        }

        is RewindResult.Refused -> {
            stringResource(
                when (result.reason) {
                    RewindRefusal.EMPTY_POINT -> R.string.rewind_refused_empty
                    RewindRefusal.POINT_GONE -> R.string.rewind_refused_gone
                    RewindRefusal.NO_HISTORY -> R.string.rewind_refused_none
                    RewindRefusal.LOCAL_UNAVAILABLE -> R.string.rewind_refused_local
                    RewindRefusal.REMOTE_UNAVAILABLE -> R.string.rewind_refused_drive
                },
            )
        }

        is RewindResult.NeedsUser -> {
            stringResource(R.string.rewind_stopped, result.detail)
        }

        is RewindResult.Retry -> {
            stringResource(R.string.rewind_stopped, result.detail)
        }
    }
