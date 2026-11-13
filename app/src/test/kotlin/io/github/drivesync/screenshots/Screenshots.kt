package io.github.drivesync.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.drivesync.core.ui.theme.DriveSyncTheme
import io.github.drivesync.core.ui.theme.SeedColors
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders screens to PNG on the JVM (Roborazzi + Robolectric native graphics), on a Pixel-sized screen.
 * Uses a plain Application so Hilt and WorkManager stay out of the way, and the static theme so images are stable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
abstract class Screenshots {
    @get:Rule
    val compose = createComposeRule()

    protected fun snap(
        name: String,
        dark: Boolean = false,
        seed: Int = SeedColors.DEFAULT,
        amoled: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            DriveSyncTheme(darkTheme = dark, dynamicColor = false, seedColor = seed, amoled = amoled, content = content)
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = OPTIONS)
    }

    private companion object {
        /** Up to 0.5% of pixels may differ: anti-aliasing varies a little between machines. */
        val OPTIONS =
            RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(resultValidator = ThresholdValidator(0.005f)),
            )
    }
}
