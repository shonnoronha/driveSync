package io.github.drivesync.feature.pairs.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PairDetailRoute(
    onBack: () -> Unit,
    onOpenActivity: () -> Unit,
    viewModel: PairDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { viewModel.changeFolder(it?.toString()) }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    PairDetailScreen(
        state = state,
        actions =
            PairDetailActions(
                onBack = onBack,
                onSyncNow = viewModel::syncNow,
                onEnabled = viewModel::setEnabled,
                onDirection = viewModel::setDirection,
                onPolicy = viewModel::setPolicy,
                onMirror = viewModel::setMirror,
                onConfirmDeletes = viewModel::confirmDeletes,
                onRestoreInstead = viewModel::restoreInstead,
                onChooseFolderAgain = { picker.launch(null) },
                onRecreateDriveFolder = viewModel::recreateDriveFolder,
                onRemove = viewModel::remove,
                onOpenActivity = onOpenActivity,
            ),
    )
}
