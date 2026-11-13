package io.github.drivesync.core.drive.store

/**
 * Maps a Drive name to one path segment. Drive allows `/` in names and names like `.` or `..`, which cannot be
 * path segments. Interim mapping until the name-mapping layer lands (PRD 6.2, build step 4).
 */
internal object DriveNames {
    private const val REPLACEMENT = '_'

    fun toSegment(name: String): String {
        val cleaned = name.replace('/', REPLACEMENT)
        return when (cleaned) {
            "" -> REPLACEMENT.toString()
            ".", ".." -> REPLACEMENT + cleaned
            else -> cleaned
        }
    }

    /** True when a Drive name other than [segment] itself could map to [segment]. */
    fun mayBeMapped(segment: String): Boolean = REPLACEMENT in segment
}
