package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.RelPath

/** Paths that scans and the planner never touch (PRD 3.6, 3.9 rule 6). */
object IgnoreRules {
    /** Suffix of hidden in-progress download files: `.photo.jpg.drivesync-part`. */
    const val TEMP_SUFFIX = ".drivesync-part"

    /** Local recycle folder at the pair root (PRD 3.9 rule 10). */
    const val RECYCLE_FOLDER = ".drivesync-recycle"

    private val ignoredSuffixes = listOf(".tmp", ".crdownload", ".part", TEMP_SUFFIX)

    fun isIgnored(path: RelPath): Boolean =
        path.segments.first() == RECYCLE_FOLDER || path.segments.any(::isIgnoredName)

    fun isIgnoredName(name: String): Boolean =
        ignoredSuffixes.any { name.endsWith(it, ignoreCase = true) } ||
            name.startsWith("~$") ||
            name == ".thumbnails" ||
            name.startsWith(".trashed-")

    /** Hidden temp path a download streams into before verify + rename. */
    fun tempPathFor(path: RelPath): RelPath = path.sibling(".${path.name}$TEMP_SUFFIX")
}
