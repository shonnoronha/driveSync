package io.github.drivesync.feature.pairs.detail

import io.github.drivesync.core.sync.model.FileFilters

/** Pure helpers for the "Skip files" dialog (FR-P5). */
object FileRules {
    private const val MB = 1024L * 1024
    private const val GB = 1024 * MB

    /** Size limits offered in the dialog; null means no limit. */
    val SIZE_CHOICES: List<Long?> = listOf(null, 10 * MB, 100 * MB, GB, 4 * GB)

    /** One pattern per line; blank lines dropped, spaces around each pattern trimmed. */
    fun parsePatterns(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun patternsText(filters: FileFilters): String = filters.exclude.joinToString("\n")

    /** "100 MB", "4 GB" for the size choices. */
    fun sizeLabel(bytes: Long): String = if (bytes >= GB && bytes % GB == 0L) "${bytes / GB} GB" else "${bytes / MB} MB"

    /** Short summary parts: pattern count, size limit, hidden files. Empty when nothing is skipped. */
    fun summaryParts(filters: FileFilters): Summary =
        Summary(
            patterns = filters.exclude.count { it.isNotBlank() && !it.trimStart().startsWith("#") },
            maxSize = filters.maxFileSize?.let(::sizeLabel),
            hidden = filters.skipHidden,
        )

    data class Summary(
        val patterns: Int,
        val maxSize: String?,
        val hidden: Boolean,
    ) {
        val isEmpty: Boolean get() = patterns == 0 && maxSize == null && !hidden
    }
}
