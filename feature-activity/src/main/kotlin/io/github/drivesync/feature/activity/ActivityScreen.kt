package io.github.drivesync.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.EmptyState
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.RootTopBar
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.Tokens
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Activity log (FR-M2, PRD 8.6): a filterable timeline across all pairs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onFilter: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { RootTopBar(stringResource(R.string.activity_title), scroll) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Filters(state.filter, onFilter)
            when {
                state.loading -> {
                    LoadingState()
                }

                state.days.isEmpty() -> {
                    EmptyState(
                        Sym.History,
                        stringResource(R.string.activity_empty_title),
                        stringResource(R.string.activity_empty_body),
                    )
                }

                else -> {
                    Timeline(state)
                }
            }
        }
    }
}

@Composable
private fun Filters(
    selected: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
) {
    Row(
        Modifier
            .horizontalScroll(
                rememberScrollState(),
            ).padding(horizontal = Tokens.pageInset, vertical = Tokens.gapXs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapSm),
    ) {
        ActivityFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onFilter(filter) },
                label = { Text(filter.label()) },
                leadingIcon = if (isSelected) ({ SymIcon(Sym.Check, null, Modifier.padding(0.dp)) }) else null,
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(Tokens.radiusControl),
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun Timeline(state: ActivityUiState) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    LazyColumn(contentPadding = PaddingValues(bottom = Tokens.gapXl)) {
        state.days.forEach { day ->
            item(key = day.date.toString()) {
                SettingsSectionHeader(
                    if (day.date ==
                        state.today
                    ) {
                        stringResource(R.string.activity_today)
                    } else {
                        day.date.format(dateFormat)
                    },
                )
                SettingsGroup { day.rows.forEach { row -> item { ActivityRowItem(row, showPair = true) } } }
            }
        }
    }
}

@Composable
private fun ActivityFilter.label(): String =
    stringResource(
        when (this) {
            ActivityFilter.ALL -> R.string.activity_filter_all
            ActivityFilter.TRANSFERS -> R.string.activity_filter_transfers
            ActivityFilter.DELETES -> R.string.activity_filter_deletes
            ActivityFilter.CONFLICTS -> R.string.activity_filter_conflicts
            ActivityFilter.PROBLEMS -> R.string.activity_filter_problems
        },
    )
