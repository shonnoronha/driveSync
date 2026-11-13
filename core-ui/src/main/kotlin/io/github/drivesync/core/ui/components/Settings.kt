package io.github.drivesync.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.theme.Tokens

/** The neutral fill of grouped rows: the page color, a touch darker (light) or lighter (dark). */
@Composable
fun groupColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.luminanceIsDark()) {
        Color.White.copy(alpha = DARK_GROUP_TINT).compositeOver(surface)
    } else {
        Color.Black.copy(alpha = LIGHT_GROUP_TINT).compositeOver(surface)
    }
}

private fun Color.luminanceIsDark(): Boolean = luminance() < DARK_LUMINANCE

private const val DARK_LUMINANCE = 0.5f
private const val DARK_GROUP_TINT = 0.08f
private const val LIGHT_GROUP_TINT = 0.04f

/** Collects the rows of a [SettingsGroup]. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun item(row: @Composable () -> Unit) {
        rows += row
    }
}

/**
 * Expressive connected list: every row is its own tile, 2dp apart. The group's ends keep the card radius and
 * inner corners stay tight, so the gaps replace dividers.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: SettingsGroupScope.() -> Unit,
) {
    val rows = SettingsGroupScope().apply(content).rows
    val color = groupColor()
    Column(
        modifier.fillMaxWidth().padding(horizontal = Tokens.pageInset, vertical = Tokens.gapXs),
        verticalArrangement = Arrangement.spacedBy(Tokens.connectedGap),
    ) {
        rows.forEachIndexed { index, row ->
            Surface(color = color, shape = connectedShape(first = index == 0, last = index == rows.lastIndex)) { row() }
        }
    }
}

fun connectedShape(
    first: Boolean,
    last: Boolean,
) = RoundedCornerShape(
    topStart = if (first) Tokens.radiusCard else Tokens.radiusConnectedInner,
    topEnd = if (first) Tokens.radiusCard else Tokens.radiusConnectedInner,
    bottomStart = if (last) Tokens.radiusCard else Tokens.radiusConnectedInner,
    bottomEnd = if (last) Tokens.radiusCard else Tokens.radiusConnectedInner,
)

/** A section title above a group, aligned with the row text. */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .padding(
                    start = Tokens.pageInset + Tokens.gapLg,
                    end = Tokens.pageInset,
                    top = Tokens.gapXl,
                    bottom = Tokens.gapSm,
                ).semantics { heading() },
    )
}

/** A settings row: optional icon, title, subtitle, and a trailing control or a chevron when tappable. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Tokens.rowPaddingH, vertical = Tokens.rowPaddingV),
        // Rows that embed a control below their title keep the icon beside the title.
        verticalAlignment = if (footer != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SymIcon(icon, contentDescription = null, tint = iconTint)
            Spacer(Modifier.width(Tokens.rowIconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (footer != null) {
                Spacer(Modifier.height(Tokens.gapMd))
                footer()
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(Tokens.gapSm))
                trailing()
            }

            onClick != null -> {
                Spacer(Modifier.width(Tokens.gapSm))
                SymIcon(Sym.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A settings row with a switch; the whole row toggles. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Tokens.rowPaddingH, vertical = Tokens.rowPaddingVCompact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SymIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Tokens.rowIconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Tokens.gapSm))
        DsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A tonal rounded square holding an icon, used as the leading visual of cards. */
@Composable
fun IconTile(
    icon: Int,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(Tokens.radiusIcon),
        modifier = modifier.size(44.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            SymIcon(icon, contentDescription = null)
        }
    }
}
