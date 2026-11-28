package io.github.drivesync.feature.pairs.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
    onOpenFiles: () -> Unit,
    onOpenRewind: () -> Unit = {},
    viewModel: PairDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { viewModel.changeFolder(it?.toString()) }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    val consent by viewModel.consentRequests.collectAsStateWithLifecycle()
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onConsentResult(it.data)
        }
    LaunchedEffect(consent) { consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
    PairDetailScreen(
        state = state,
        actions =
            PairDetailActions(
                onBack = onBack,
                onSyncNow = viewModel::syncNow,
                onEnabled = viewModel::setEnabled,
                onDirection = viewModel::setDirection,
                onPolicy = { policy -> viewModel.edit { it.copy(conflictPolicy = policy) } },
                onMirror = { mirror -> viewModel.edit { it.copy(mirrorDeletions = mirror) } },
                onFilters = { filters -> viewModel.edit { it.copy(filters = filters) } },
                onEdit = viewModel::edit,
                onConfirmDeletes = viewModel::confirmDeletes,
                onRestoreInstead = viewModel::restoreInstead,
                onChooseFolderAgain = { picker.launch(null) },
                onRecreateDriveFolder = viewModel::recreateDriveFolder,
                onGrantGoogleAccess = viewModel::grantGoogleAccess,
                onRemove = viewModel::remove,
                onOpenActivity = onOpenActivity,
                onOpenFiles = onOpenFiles,
                onOpenRewind = onOpenRewind,
            ),
    )
}
