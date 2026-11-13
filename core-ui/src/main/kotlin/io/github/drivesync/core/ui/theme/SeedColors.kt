package io.github.drivesync.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/** Accent choices when wallpaper colors are off or unavailable (before Android 12). */
object SeedColors {
    const val BLUE = 0xFF3D7BF7.toInt()
    const val TEAL = 0xFF14A3A3.toInt()
    const val GREEN = 0xFF2E9E5B.toInt()
    const val YELLOW = 0xFFE0A100.toInt()
    const val ORANGE = 0xFFF2741F.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val PINK = 0xFFE5579C.toInt()
    const val PURPLE = 0xFF8A5CF6.toInt()

    val ALL = listOf(BLUE, TEAL, GREEN, YELLOW, ORANGE, RED, PINK, PURPLE)
    const val DEFAULT = BLUE
}

/** A full Material 3 scheme from one seed, the way Android builds wallpaper schemes (tonal spot). */
fun seedColorScheme(
    seed: Int,
    dark: Boolean,
): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val roles = MaterialDynamicColors()

    fun DynamicColor.color() = Color(getArgb(scheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = roles.primary.color(),
        onPrimary = roles.onPrimary.color(),
        primaryContainer = roles.primaryContainer.color(),
        onPrimaryContainer = roles.onPrimaryContainer.color(),
        inversePrimary = roles.inversePrimary.color(),
        secondary = roles.secondary.color(),
        onSecondary = roles.onSecondary.color(),
        secondaryContainer = roles.secondaryContainer.color(),
        onSecondaryContainer = roles.onSecondaryContainer.color(),
        tertiary = roles.tertiary.color(),
        onTertiary = roles.onTertiary.color(),
        tertiaryContainer = roles.tertiaryContainer.color(),
        onTertiaryContainer = roles.onTertiaryContainer.color(),
        background = roles.background.color(),
        onBackground = roles.onBackground.color(),
        surface = roles.surface.color(),
        onSurface = roles.onSurface.color(),
        surfaceVariant = roles.surfaceVariant.color(),
        onSurfaceVariant = roles.onSurfaceVariant.color(),
        surfaceTint = roles.surfaceTint.color(),
        inverseSurface = roles.inverseSurface.color(),
        inverseOnSurface = roles.inverseOnSurface.color(),
        error = roles.error.color(),
        onError = roles.onError.color(),
        errorContainer = roles.errorContainer.color(),
        onErrorContainer = roles.onErrorContainer.color(),
        outline = roles.outline.color(),
        outlineVariant = roles.outlineVariant.color(),
        scrim = roles.scrim.color(),
        surfaceBright = roles.surfaceBright.color(),
        surfaceDim = roles.surfaceDim.color(),
        surfaceContainer = roles.surfaceContainer.color(),
        surfaceContainerHigh = roles.surfaceContainerHigh.color(),
        surfaceContainerHighest = roles.surfaceContainerHighest.color(),
        surfaceContainerLow = roles.surfaceContainerLow.color(),
        surfaceContainerLowest = roles.surfaceContainerLowest.color(),
    )
}

/** Pure black pages for OLED screens; containers stay a step above black so cards keep their shape. */
fun ColorScheme.amoled(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(AMOLED_LOW),
        surfaceContainer = Color(AMOLED_MID),
        surfaceContainerHigh = Color(AMOLED_HIGH),
    )

private const val AMOLED_LOW = 0xFF0E0E10
private const val AMOLED_MID = 0xFF151518
private const val AMOLED_HIGH = 0xFF1D1D21
