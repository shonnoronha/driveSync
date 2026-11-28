package io.github.drivesync.feature.settings.settings

import java.util.Locale
import kotlin.math.roundToLong

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")
private const val STEP = 1024.0
private const val WHOLE_FROM = 100

/** "512 B", "1.5 GB", "15 GB", "120 GB": one decimal below 100 when it isn't zero (1024-based, like Google's UI). */
fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= STEP && unit < UNITS.lastIndex) {
        value /= STEP
        unit++
    }
    val rounded = String.format(Locale.ROOT, "%.1f", value)
    val text = if (unit == 0 || value >= WHOLE_FROM || rounded.endsWith(".0")) "${value.roundToLong()}" else rounded
    return "$text ${UNITS[unit]}"
}
