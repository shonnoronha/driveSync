package io.github.drivesync.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** One radius and spacing scale for the whole app. */
object Tokens {
    val radiusBadge = 6.dp
    val radiusThumb = 8.dp
    val radiusIcon = 12.dp
    val radiusControl = 16.dp
    val radiusCard = 20.dp
    val radiusSheet = 28.dp

    /** Inner corners of rows in a connected group; the group's ends keep [radiusCard]. */
    val radiusConnectedInner = 4.dp

    /** Gap between rows of a connected group (it replaces dividers). */
    val connectedGap = 2.dp

    val gapXs = 4.dp
    val gapSm = 8.dp
    val gapMd = 12.dp
    val gapLg = 16.dp
    val gapXl = 24.dp

    /** Page edge inset of groups and cards. */
    val pageInset = 16.dp
    val rowPaddingH = 20.dp
    val rowPaddingV = 16.dp
    val rowPaddingVCompact = 12.dp
    val rowIconGap = 16.dp
}

val DriveSyncShapes =
    Shapes(
        extraSmall = RoundedCornerShape(Tokens.radiusThumb),
        small = RoundedCornerShape(Tokens.radiusIcon),
        medium = RoundedCornerShape(Tokens.radiusControl),
        large = RoundedCornerShape(Tokens.radiusCard),
        extraLarge = RoundedCornerShape(Tokens.radiusSheet),
    )
