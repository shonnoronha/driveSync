package io.github.drivesync.feature.settings.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.core.ui.components.ConnectedToggleGroup
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.SettingsSectionHeader
import io.github.drivesync.core.ui.components.SettingsSwitchRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.SeedColors
import io.github.drivesync.core.ui.theme.Tokens
import io.github.drivesync.feature.settings.R

@Composable
internal fun AppearanceSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val s = state.settings
    SettingsSectionHeader(stringResource(R.string.settings_appearance))
    SettingsGroup {
        item {
            SettingsRow(
                title = stringResource(R.string.settings_theme),
                icon = Sym.Contrast,
                footer = {
                    ConnectedToggleGroup(ThemeMode.entries, s.theme, {
                        actions.onTheme(it, s.dynamicColor)
                    }) { Text(it.label(), maxLines = 1) }
                },
            )
        }
        item {
            SettingsSwitchRow(
                stringResource(R.string.settings_dynamic_color),
                s.dynamicColor,
                { actions.onTheme(s.theme, it) },
                subtitle = stringResource(R.string.settings_dynamic_color_body),
                icon = Sym.Palette,
            )
        }
        if (!s.dynamicColor) {
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_accent),
                    icon = Sym.ColorFill,
                    footer = { Swatches(s.seedColor) { actions.onAccent(it, s.amoled) } },
                )
            }
        }
        item {
            SettingsSwitchRow(
                stringResource(R.string.settings_amoled),
                s.amoled,
                { actions.onAccent(s.seedColor, it) },
                subtitle = stringResource(R.string.settings_amoled_body),
                icon = Sym.DarkMode,
            )
        }
    }
}

@Composable
private fun Swatches(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.gapMd),
        verticalArrangement = Arrangement.spacedBy(Tokens.gapMd),
    ) {
        SeedColors.ALL.forEach { seed ->
            val isSelected = seed == selected
            Surface(
                shape = CircleShape,
                color = Color(seed),
                modifier =
                    Modifier
                        .size(40.dp)
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    3.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ).clickable { onSelect(seed) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                            contentDescription = "#%06X".format(seed and RGB_MASK)
                        },
            ) {
                if (isSelected) {
                    Box(
                        contentAlignment = Alignment.Center,
                    ) { SymIcon(Sym.Check, contentDescription = null, tint = Color.White) }
                }
            }
        }
    }
}

/** A row whose value is picked from a dropdown menu. */
@Composable
internal fun <T> MenuRow(
    title: String,
    icon: Int,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title = title, subtitle = label(value), icon = icon, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach {
                DropdownMenuItem(text = { Text(label(it)) }, onClick = {
                    open = false
                    onPick(it)
                })
            }
        }
    }
}

@Composable
internal fun intervalLabel(minutes: Int): String =
    when {
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.settings_minutes, minutes)
        minutes == MINUTES_PER_HOUR -> stringResource(R.string.settings_hour)
        else -> stringResource(R.string.settings_hours, minutes / MINUTES_PER_HOUR)
    }

private const val MINUTES_PER_HOUR = 60
private const val RGB_MASK = 0xFFFFFF

@Composable
private fun ThemeMode.label(): String =
    stringResource(
        when (this) {
            ThemeMode.SYSTEM -> R.string.settings_theme_system
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )
