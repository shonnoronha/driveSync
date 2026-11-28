package io.github.drivesync.core.drive.api

/** Storage of the signed-in account (FR-M5). [limitBytes] is null for unlimited plans. */
data class DriveStorage(
    val usedBytes: Long,
    val limitBytes: Long?,
) {
    /** 0..1, or null when there is no limit. */
    val fractionUsed: Float?
        get() = limitBytes?.takeIf { it > 0 }?.let { (usedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    /** Uploads start failing once storage is full; warn a little before that. */
    val nearlyFull: Boolean get() = (fractionUsed ?: 0f) >= NEARLY_FULL

    companion object {
        const val NEARLY_FULL = 0.9f
    }
}
