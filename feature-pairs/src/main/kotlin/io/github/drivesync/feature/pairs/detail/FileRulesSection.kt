package io.github.drivesync.feature.pairs.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.ui.components.DsTextField
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.pairs.R

/** "Skip files" row of the folder options (FR-P5): a summary, and a dialog to edit the rules. */
@Composable
internal fun FileRulesRow(
    filters: FileFilters,
    onFilters: (FileFilters) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.rules_title),
        subtitle = summaryText(filters),
        icon = Sym.Block,
        onClick = { editing = true },
    )
    if (editing) {
        FileRulesDialog(filters, onDismiss = { editing = false }) {
            onFilters(it)
            editing = false
        }
    }
}

@Composable
private fun summaryText(filters: FileFilters): String {
    val summary = FileRules.summaryParts(filters)
    if (summary.isEmpty) return stringResource(R.string.rules_none)
    val count = summary.patterns
    return buildList {
        if (count > 0) add(pluralStringResource(R.plurals.rules_patterns, count, count))
        summary.maxSize?.let { add(stringResource(R.string.rules_over, it)) }
        if (summary.hidden) add(stringResource(R.string.rules_hidden_short))
    }.joinToString(" · ")
}

@Composable
private fun FileRulesDialog(
    current: FileFilters,
    onDismiss: () -> Unit,
    onSave: (FileFilters) -> Unit,
) {
    var patterns by remember { mutableStateOf(FileRules.patternsText(current)) }
    var maxSize by remember { mutableStateOf(current.maxFileSize) }
    var hidden by remember { mutableStateOf(current.skipHidden) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Tokens.gapSm),
            ) {
                Text(stringResource(R.string.rules_body), style = MaterialTheme.typography.bodyMedium)
                DsTextField(
                    patterns,
                    { patterns = it },
                    stringResource(R.string.rules_patterns_label),
                    placeholder = stringResource(R.string.rules_patterns_placeholder),
                    supportingText = stringResource(R.string.rules_patterns_help),
                    minLines = 3,
                )
                SizeLimitRow(maxSize) { maxSize = it }
                SettingsSwitchRow(stringResource(R.string.rules_hidden), hidden, { hidden = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(FileFilters(FileRules.parsePatterns(patterns), maxSize, hidden))
            }) { Text(stringResource(R.string.rules_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) } },
    )
}

@Composable
private fun SizeLimitRow(
    maxSize: Long?,
    onMaxSize: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = stringResource(R.string.rules_size),
            subtitle = sizeChoiceLabel(maxSize),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FileRules.SIZE_CHOICES.forEach { choice ->
                DropdownMenuItem(text = { Text(sizeChoiceLabel(choice)) }, onClick = {
                    onMaxSize(choice)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun sizeChoiceLabel(bytes: Long?): String =
    if (bytes == null) {
        stringResource(R.string.rules_size_none)
    } else {
        stringResource(R.string.rules_over, FileRules.sizeLabel(bytes))
    }
