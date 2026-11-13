package io.github.drivesync.feature.pairs.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeRoute(
    onAddPair: () -> Unit,
    onOpenPair: (String) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state,
        HomeActions(
            onAddPair = onAddPair,
            onOpenPair = onOpenPair,
            onSyncAll = viewModel::syncAll,
            onSyncPair = viewModel::syncPair,
            onOpenConflicts = onOpenConflicts,
            onOpenActivity = onOpenActivity,
            onOpenSettings = onOpenSettings,
        ),
    )
}
