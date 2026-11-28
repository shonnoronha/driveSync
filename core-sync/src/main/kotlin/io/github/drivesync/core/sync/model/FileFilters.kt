package io.github.drivesync.core.sync.model

/**
 * Per-pair rules for files that should not sync (FR-P5). Excluded paths are invisible to the planner on **both**
 * sides, so adding a rule never turns into a delete: files that already synced stay where they are on the phone and
 * on Drive, they just stop syncing. Removing the rule later merges them back (link, copy or keep-both).
 */
data class FileFilters(
    /**
     * Patterns, one per entry, matched without regard to case:
     * - a pattern without `/` matches a file or folder name at any depth: `*.tmp`, `node_modules`, `Thumbs.db`;
     * - a pattern with `/` matches the path from the folder's root, like `Camera/old`;
     * - `*` matches within a name, `**` across folders, `?` one character; a trailing `/` is optional.
     * Excluding a folder excludes everything inside it. Blank entries and entries starting with `#` are ignored.
     */
    val exclude: List<String> = emptyList(),
    /** Files bigger than this (on either side) are not synced. Null means no limit. */
    val maxFileSize: Long? = null,
    /** Skip files and folders whose name starts with a dot. */
    val skipHidden: Boolean = false,
    /**
     * False syncs only the files directly in the folder (FR-P4). Subfolders are then excluded like any other rule,
     * so turning this off never deletes them on either side.
     */
    val includeSubfolders: Boolean = true,
) {
    private val patterns: List<FilePattern> by lazy { exclude.mapNotNull(FilePattern::parse) }

    val isEmpty: Boolean get() = patterns.isEmpty() && maxFileSize == null && !skipHidden && includeSubfolders

    /** By path alone (patterns, hidden names, depth), checking the path and every folder above it. */
    fun excludesPath(path: RelPath): Boolean {
        if (!includeSubfolders && path.segments.size > 1) return true
        if (skipHidden && path.segments.any { it.startsWith(".") }) return true
        return patterns.any { it.matches(path) }
    }

    fun excludes(
        path: RelPath,
        isFolder: Boolean,
        size: Long,
    ): Boolean =
        excludesPath(path) ||
            (isFolder && !includeSubfolders) ||
            (!isFolder && maxFileSize != null && size > maxFileSize)

    /**
     * Every path either side (or the base) says is excluded. Deciding per path, from both sides together, keeps a
     * size rule from hiding a file on one side only, which the planner would read as a delete.
     */
    fun excludedPaths(
        local: Collection<LocalEntry>,
        remote: Collection<RemoteEntry>,
        base: Collection<BaseEntry> = emptyList(),
    ): Set<RelPath> {
        if (isEmpty) return emptySet()
        val out = HashSet<RelPath>()
        local.filter { excludes(it.path, it.isFolder, it.size) }.mapTo(out) { it.path }
        remote.filter { excludes(it.path, it.isFolder, it.size) }.mapTo(out) { it.path }
        base.filter { excludesPath(it.path) || (it.isFolder && !includeSubfolders) }.mapTo(out) { it.path }
        return out
    }
}

/** One glob pattern of [FileFilters.exclude]. */
internal class FilePattern private constructor(
    private val regex: Regex,
    /** Matches whole paths from the root; otherwise single names at any depth. */
    private val anchored: Boolean,
) {
    fun matches(path: RelPath): Boolean =
        if (anchored) {
            regex.matches(path.value) || path.ancestors.any { regex.matches(it.value) }
        } else {
            path.segments.any { regex.matches(it) }
        }

    companion object {
        private const val ANY_FOLDERS = "**/"
        private const val ANY_PATH = "**"

        fun parse(raw: String): FilePattern? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            // "Camera/old/**" means the folder and everything in it, the same as "Camera/old".
            val body = trimmed.removeSuffix("/**").trim('/')
            if (body.isEmpty() || body == "**") return null
            return FilePattern(Regex(toRegex(body), RegexOption.IGNORE_CASE), anchored = '/' in body)
        }

        private fun toRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    glob.startsWith(ANY_FOLDERS, i) -> {
                        out.append("(?:.*/)?")
                        i += ANY_FOLDERS.length
                    }

                    glob.startsWith(ANY_PATH, i) -> {
                        out.append(".*")
                        i += ANY_PATH.length
                    }

                    c == '*' -> {
                        out.append("[^/]*")
                        i++
                    }

                    c == '?' -> {
                        out.append("[^/]")
                        i++
                    }

                    else -> {
                        out.append(Regex.escape(c.toString()))
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
