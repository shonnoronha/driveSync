package io.github.drivesync.feature.pairs.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.feature.pairs.R

/** Add-folder wizard (PRD 8.3, FR-P1): folder → name and Drive folder → options → review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPairScreen(
    state: AddPairUiState,
    actions: AddPairActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onClose,
                    ) { Icon(Icons.Outlined.Close, stringResource(R.string.add_close)) }
                },
            )
        },
        bottomBar = { WizardButtons(state, actions) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val steps = AddStep.entries.size
            LinearProgressIndicator(
                progress = { (state.step.ordinal + 1f) / steps },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.add_step, state.step.ordinal + 1, steps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.step) {
                    AddStep.FOLDER -> FolderStep(state, actions)
                    AddStep.NAME -> NameStep(state, actions)
                    AddStep.OPTIONS -> OptionsStep(state, actions)
                    AddStep.REVIEW -> ReviewStep(state)
                }
            }
        }
    }
}

@Composable
private fun WizardButtons(
    state: AddPairUiState,
    actions: AddPairActions,
) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
        if (AddPairRules.previous(state.step) != null) {
            TextButton(onClick = actions.onBack, enabled = !state.working) { Text(stringResource(R.string.add_back)) }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = actions.onNext, enabled = state.canContinue) {
            Text(stringResource(if (state.step == AddStep.REVIEW) R.string.add_create else R.string.add_next))
        }
    }
}

@Composable
internal fun StepTitle(
    title: String,
    body: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
