package io.github.drivesync.feature.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.EmptyState
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Activity log (FR-M2, PRD 8.6): a filterable timeline across all pairs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onFilter: (ActivityFilter) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.activity_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Filters(state.filter, onFilter)
            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                state.days.isEmpty() -> {
                    EmptyState(
                        Icons.Outlined.History,
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
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onFilter(filter) }, label = { Text(filter.label()) })
        }
    }
}

@Composable
private fun Timeline(state: ActivityUiState) {
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    LazyColumn {
        state.days.forEach { day ->
            item(key = day.date.toString()) {
                Text(
                    if (day.date ==
                        state.today
                    ) {
                        stringResource(R.string.activity_today)
                    } else {
                        day.date.format(dateFormat)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(day.rows, key = { it.id }) { ActivityRowItem(it, showPair = true) }
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
