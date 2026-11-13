package io.github.drivesync.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A small pill with a colored dot: "Up to date", "Needs attention", ... */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content, dot) =
        when (tone) {
            StatusTone.OK -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.primary)
            StatusTone.WORKING -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
            StatusTone.WARNING -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
            StatusTone.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, colors.error)
            StatusTone.NEUTRAL -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
        }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Dot(dot)
            Box(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
