package io.github.drivesync.feature.activity

import io.github.drivesync.core.data.db.ActivityEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the shared log says about the app and phone, so a bug report needs no follow-up questions. */
data class ExportHeader(
    val appVersion: String,
    val android: String,
    val device: String,
    /** Times in the log are written in this zone, with the offset. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * The activity log as plain text for bug reports (FR-M2): newest first, the same filter as the screen, at most
 * [MAX_EXPORT_ROWS] lines so it fits in a share intent. With [hideNames], folder, file and pair names become
 * placeholders ("Folder 1", "name3.jpg") that stay the same within one export, so the log still shows which lines
 * are about the same file.
 */
fun activityLogText(
    entries: List<ActivityEntity>,
    pairNames: Map<String, String>,
    filter: ActivityFilter,
    hideNames: Boolean,
    header: ExportHeader,
): String {
    val matching =
        entries
            .filter { filter.kinds == null || it.kind in filter.kinds }
            .sortedWith(compareByDescending<ActivityEntity> { it.time }.thenByDescending { it.id })
    val shown = matching.take(MAX_EXPORT_ROWS)
    val names = NameHider(hideNames)
    // Names are learned from the rows first, so a name that only shows up inside a detail is hidden too.
    shown.forEach { row ->
        names.pair(pairNames[row.pairId].orEmpty())
        row.path?.let(names::path)
    }
    val time = DateTimeFormatter.ofPattern(TIME_PATTERN).withZone(header.zone)
    return buildString {
        appendLine("Drive Sync activity log")
        appendLine("App ${header.appVersion} · Android ${header.android} · ${header.device}")
        append("Filter: ${filter.name.lowercase()} · entries: ")
        append(if (shown.size < matching.size) "newest ${shown.size} of ${matching.size}" else "${shown.size}")
        if (hideNames) append(" · names hidden")
        appendLine()
        appendLine()
        shown.forEach { row ->
            append(time.format(Instant.ofEpochMilli(row.time)))
            append(SEPARATOR).append(row.kind.name)
            append(SEPARATOR).append(names.pair(pairNames[row.pairId].orEmpty()).ifEmpty { "(removed folder)" })
            row.path?.let { append(SEPARATOR).append(names.path(it)) }
            row.detail?.let { append(SEPARATOR).append(names.text(it)) }
            appendLine()
        }
    }
}

const val MAX_EXPORT_ROWS = 1_000
private const val SEPARATOR = " | "
private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss xxx"

/** Swaps names for stable placeholders; does nothing when [enabled] is false. */
internal class NameHider(
    private val enabled: Boolean,
) {
    private val pairs = LinkedHashMap<String, String>()
    private val segments = LinkedHashMap<String, String>()

    fun pair(name: String): String {
        if (!enabled || name.isEmpty()) return name
        return pairs.getOrPut(name) { "Folder ${pairs.size + 1}" }
    }

    fun path(path: String): String =
        if (!enabled) {
            path
        } else {
            path.split('/').joinToString("/") { segment(it) }
        }

    /** Hides known names inside free text (error messages), plus anything that looks like an email address. */
    fun text(text: String): String {
        if (!enabled) return text
        val withoutEmails = EMAIL.replace(text, "[email]")
        // Pair names win over file names; one pass, so a placeholder is never replaced again.
        val tokens = (segments + pairs).filterKeys { it.length >= MIN_NAME_IN_TEXT }
        if (tokens.isEmpty()) return withoutEmails
        val alternatives = tokens.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val names = Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])")
        return names.replace(withoutEmails) { tokens.getValue(it.value) }
    }

    private fun segment(name: String): String {
        if (name.isEmpty()) return name
        return segments.getOrPut(name) { "name${segments.size + 1}${extensionOf(name)}" }
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        val ext = name.substring(dot + 1)
        return if (ext.length in 1..MAX_EXTENSION && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    private companion object {
        const val MIN_NAME_IN_TEXT = 3
        const val MAX_EXTENSION = 5
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}
