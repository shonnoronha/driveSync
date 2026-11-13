package io.github.drivesync.feature.pairs.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.LoadingState
import io.github.drivesync.core.ui.components.SubTopBar
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.feature.pairs.R

/** Pair detail (PRD 8.4): status and fixes, options, pause, recent activity, remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDetailScreen(
    state: PairDetailUiState,
    actions: PairDetailActions,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SubTopBar(
                state.pair?.name.orEmpty(),
                Sym.ArrowBack,
                stringResource(R.string.detail_back),
                actions.onBack,
                scroll,
            )
        },
    ) { padding ->
        val pair = state.pair
        if (state.loading || pair == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            StatusSection(pair, state, actions)
            FolderSection(pair)
            OptionsSection(state, actions)
            RecentSection(state, actions)
            RemoveSection(pair.name, actions)
        }
    }
}
