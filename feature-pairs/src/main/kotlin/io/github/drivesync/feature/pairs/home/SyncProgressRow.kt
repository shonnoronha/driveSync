package io.github.drivesync.feature.pairs.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.core.work.android.PairProgress
import io.github.drivesync.feature.pairs.R

/** "Checking for changes…" until the run knows its work, then "3 / 182 files" over a filling wavy bar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SyncProgressRow(progress: PairProgress?) {
    val done = progress?.done
    val total = progress?.total
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.gapSm)) {
        Text(
            if (done != null && total != null && total > 0) {
                stringResource(R.string.pair_progress, done, total)
            } else {
                stringResource(R.string.pair_checking)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (done != null && total != null && total > 0) {
            LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
