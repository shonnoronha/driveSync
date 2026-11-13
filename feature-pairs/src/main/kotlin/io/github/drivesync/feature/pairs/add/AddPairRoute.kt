package io.github.drivesync.feature.pairs.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The wizard wired to its ViewModel and the system folder picker. */
@Composable
fun AddPairRoute(
    onClose: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: AddPairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            viewModel.onFolderPicked(uri?.toString())
        }
    LaunchedEffect(state.createdPairId) { state.createdPairId?.let(onCreated) }
    AddPairScreen(
        state = state,
        actions =
            AddPairActions(
                onClose = onClose,
                onBack = viewModel::back,
                onNext = viewModel::next,
                onPickFolder = { picker.launch(null) },
                onName = viewModel::onName,
                onTarget = viewModel::onTarget,
                onDirection = viewModel::onDirection,
                onPolicy = viewModel::onPolicy,
                onMirror = viewModel::onMirror,
            ),
    )
}
