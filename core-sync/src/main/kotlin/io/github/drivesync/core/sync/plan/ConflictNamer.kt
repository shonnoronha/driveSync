package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.RelPath
import java.time.format.DateTimeFormatter

/**
 * Builds conflict-copy names such as `report (conflict 2026-11-12 14-30 Pixel).docx` (PRD 3.5), unique
 * against every path in [taken] and every name it handed out before.
 */
class ConflictNamer(
    stamp: ConflictStamp,
    taken: Set<RelPath>,
) {
    private val used = taken.toMutableSet()
    private val label = "conflict ${stamp.time.format(FORMAT)} ${sanitize(stamp.deviceName)}"

    fun conflictPath(original: RelPath): RelPath {
        val name = original.name
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var candidate = original.sibling("$stem ($label)$ext")
        var n = 2
        while (candidate in used) {
            candidate = original.sibling("$stem ($label $n)$ext")
            n++
        }
        used += candidate
        return candidate
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
        val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

        fun sanitize(device: String): String = device.replace(UNSAFE, "_").trim().ifEmpty { "device" }
    }
}
