package io.github.drivesync.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.R
import org.junit.Test

/** The launcher icon as launchers mask it: rounded square, circle, and the Android 13+ themed (monochrome) icon. */
class IconScreenshots : Screenshots() {
    @Composable
    private fun Icon(
        shape: Shape,
        themed: Boolean = false,
    ) {
        // A 108dp adaptive layer drawn at 144dp; the mask shows the middle 2/3, as on a home screen.
        Box(
            Modifier.size(96.dp).clip(shape).background(if (themed) Color(0xFF1F2A3D) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (!themed) Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(144.dp))
            Image(
                painterResource(if (themed) R.drawable.ic_launcher_monochrome else R.drawable.ic_launcher_foreground),
                null,
                Modifier.requiredSize(144.dp),
                colorFilter = if (themed) ColorFilter.tint(Color(0xFFB9CCFF)) else null,
            )
        }
    }

    @Test
    fun launcherIcons() =
        snap("launcher_icon") {
            Row(
                Modifier.background(Color(0xFFE9EEF6)).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Icon(RoundedCornerShape(26.dp))
                Icon(CircleShape)
                Icon(CircleShape, themed = true)
            }
        }
}
