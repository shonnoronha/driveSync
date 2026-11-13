package io.github.drivesync.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.drivesync.core.ui.R

private val WEIGHTS = listOf(400, 500, 600, 700)
private const val ROUNDED = 100f

@OptIn(ExperimentalTextApi::class)
private fun flexFamily(roundness: Float) =
    FontFamily(
        WEIGHTS.map { weight ->
            Font(
                R.font.flex_sans,
                FontWeight(weight),
                variationSettings =
                    FontVariation.Settings(
                        FontVariation.weight(weight),
                        FontVariation.Setting("ROND", roundness),
                    ),
            )
        },
    )

/** Google Sans Flex with fully rounded terminals: display, headline and title roles (Material 3 Expressive). */
val RoundedFlex: FontFamily = flexFamily(ROUNDED)

/** The same face with plain terminals for body and label text, which stays crisper at small sizes. */
val PlainFlex: FontFamily = flexFamily(0f)

private fun TextStyle.rounded() = copy(fontFamily = RoundedFlex)

private fun TextStyle.plain() = copy(fontFamily = PlainFlex)

val DriveSyncTypography: Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.rounded(),
            displayMedium = displayMedium.rounded(),
            displaySmall = displaySmall.rounded(),
            headlineLarge = headlineLarge.rounded(),
            headlineMedium = headlineMedium.rounded(),
            headlineSmall = headlineSmall.rounded(),
            titleLarge = titleLarge.rounded(),
            titleMedium = titleMedium.rounded(),
            titleSmall = titleSmall.rounded(),
            bodyLarge = bodyLarge.plain(),
            bodyMedium = bodyMedium.plain(),
            bodySmall = bodySmall.plain(),
            labelLarge = labelLarge.plain(),
            labelMedium = labelMedium.plain(),
            labelSmall = labelSmall.plain(),
        )
    }
