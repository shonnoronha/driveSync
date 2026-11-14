package io.github.drivesync.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive: colors from the wallpaper on Android 12+ (or from a seed color), Google Sans Flex,
 * the app's radius scale and spring motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DriveSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Int = SeedColors.DEFAULT,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme: ColorScheme =
        when {
            useDynamic && darkTheme -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> remember(seedColor, darkTheme) { seedColorScheme(seedColor, darkTheme) }
        }.let { if (darkTheme && amoled) it.amoled() else it }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = DriveSyncShapes,
        typography = DriveSyncTypography,
        content = content,
    )
}
