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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.SubTopBar
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.pairs.R

/** Add-folder wizard (PRD 8.3, FR-P1): folder → name and Drive folder → options → review. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddPairScreen(
    state: AddPairUiState,
    actions: AddPairActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            SubTopBar(
                stringResource(R.string.add_title),
                Sym.Close,
                stringResource(R.string.add_close),
                actions.onClose,
            )
        },
        bottomBar = { WizardButtons(state, actions) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val steps = AddStep.entries.size
            LinearWavyProgressIndicator(
                progress = { (state.step.ordinal + 1f) / steps },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.pageInset + Tokens.gapXs),
            )
            Text(
                stringResource(R.string.add_step, state.step.ordinal + 1, steps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Tokens.pageInset + Tokens.gapXs, top = Tokens.gapSm),
            )
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = Tokens.gapLg),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapMd),
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
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(Tokens.pageInset)) {
        if (AddPairRules.previous(state.step) != null) {
            TextButton(onClick = actions.onBack, enabled = !state.working) { Text(stringResource(R.string.add_back)) }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = actions.onNext, enabled = state.canContinue, contentPadding = ButtonPadding) {
            Text(stringResource(if (state.step == AddStep.REVIEW) R.string.add_create else R.string.add_next))
        }
    }
}

private val ButtonPadding =
    androidx.compose.foundation.layout
        .PaddingValues(horizontal = 28.dp, vertical = 14.dp)

@Composable
internal fun StepTitle(
    title: String,
    body: String? = null,
) {
    Column(
        Modifier.padding(horizontal = Tokens.pageInset + Tokens.gapXs),
        verticalArrangement = Arrangement.spacedBy(Tokens.gapSm),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
