package io.github.drivesync.feature.pairs.files

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.IgnoreRules

/** Where one file stands, comparing the phone folder now with the state saved at the last sync. */
enum class FileStatus {
    /** Unchanged on the phone since the last sync, so the phone and Drive copies match. */
    SYNCED,

    /** On the phone but never synced. */
    NEW_ON_PHONE,

    /** Edited on the phone since the last sync. */
    CHANGED_ON_PHONE,

    /** Synced before, but no longer in the phone folder. */
    REMOVED_FROM_PHONE,

    /** The latest run found a conflict that waits for the user's choice. */
    CONFLICT,

    /** The latest run tried to transfer the file and failed; the next run retries. */
    FAILED,

    /** On Drive but never synced: Google Docs files and the like (FR-S12). */
    SKIPPED,
    ;

    val needsSync: Boolean get() = this != SYNCED
}

/** One row of the Files screen: a file, or a folder with the number of files inside that are not synced. */
data class FileRow(
    val path: RelPath,
    val isFolder: Boolean,
    val status: FileStatus,
    val size: Long = 0,
    /** Folders only: files anywhere below that are not synced. */
    val notSynced: Int = 0,
    /** Folders only: all files anywhere below. */
    val files: Int = 0,
    /** Why a transfer failed, for [FileStatus.FAILED]. */
    val detail: String? = null,
) {
    val name: String get() = path.name
}

/** What the last run recorded about single files of a pair. */
data class RunIssues(
    /** Paths with a conflict waiting for the user's choice. */
    val pendingConflicts: Set<String> = emptySet(),
    /** Paths whose transfer failed in the latest run, with the error message. */
    val failed: Map<String, String?> = emptyMap(),
    /** Paths the latest run left alone. */
    val skipped: Set<String> = emptySet(),
)

/** Every row of one pair, by folder, plus totals. */
data class FileIndex(
    /** Rows per folder; the pair root is the `null` key. Folders first, then files, each by name. */
    val children: Map<RelPath?, List<FileRow>>,
    val totalFiles: Int,
    val counts: Map<FileStatus, Int>,
) {
    val notSynced: Int get() = counts.filterKeys { it.needsSync }.values.sum()

    fun rowsIn(folder: RelPath?): List<FileRow> = children[folder].orEmpty()

    /** Rows of [folder] that are, or contain, files that are not synced. */
    fun notSyncedIn(folder: RelPath?): List<FileRow> = rowsIn(folder).filter { it.notSynced > 0 || it.status.needsSync }

    companion object {
        val EMPTY = FileIndex(emptyMap(), 0, emptyMap())
    }
}

/**
 * Builds the Files screen from a fresh phone scan and the saved base, without touching Drive. Phone-side changes
 * show at once; Drive-side changes only show after the next sync has looked at Drive.
 */
object FileStatusRules {
    fun index(
        local: List<LocalEntry>,
        base: List<BaseEntry>,
        issues: RunIssues = RunIssues(),
    ): FileIndex {
        val baseByPath = base.associateBy { it.path }
        val files = mutableListOf<FileRow>()
        val folders = sortedSetOf<RelPath>()
        local.filterNot { IgnoreRules.isIgnored(it.path) }.forEach { entry ->
            if (entry.isFolder) {
                folders += entry.path
            } else {
                files += FileRow(entry.path, false, localStatus(entry, baseByPath[entry.path]), entry.size)
            }
        }
        val onPhone = local.mapTo(HashSet()) { it.path }
        base
            .filter { !it.isFolder && it.path !in onPhone && !IgnoreRules.isIgnored(it.path) }
            .forEach { files += FileRow(it.path, false, FileStatus.REMOVED_FROM_PHONE, it.localSize) }
        val known = files.mapTo(HashSet()) { it.path.value }
        issues.skipped
            .filter { it !in known }
            .forEach { files += FileRow(RelPath.of(it), false, FileStatus.SKIPPED) }
        val withIssues = files.map { it.withIssues(issues) }
        withIssues.forEach { row -> row.path.ancestors.forEach { folders += it } }
        return FileIndex(
            children = group(withIssues, folders),
            totalFiles = withIssues.size,
            counts = withIssues.groupingBy { it.status }.eachCount(),
        )
    }

    /** Each side only against its own base values (PRD 3.9 rule 3): size and last-modified, like the planner. */
    private fun localStatus(
        entry: LocalEntry,
        base: BaseEntry?,
    ): FileStatus =
        when {
            base == null || base.isFolder -> FileStatus.NEW_ON_PHONE
            entry.size == base.localSize && entry.lastModified == base.localLastModified -> FileStatus.SYNCED
            else -> FileStatus.CHANGED_ON_PHONE
        }

    private fun FileRow.withIssues(issues: RunIssues): FileRow {
        val key = path.value
        return when {
            key in issues.pendingConflicts -> copy(status = FileStatus.CONFLICT)
            key in issues.failed && status.needsSync -> copy(status = FileStatus.FAILED, detail = issues.failed[key])
            key in issues.skipped -> copy(status = FileStatus.SKIPPED)
            else -> this
        }
    }

    private fun group(
        files: List<FileRow>,
        folders: Set<RelPath>,
    ): Map<RelPath?, List<FileRow>> {
        val total = HashMap<RelPath, Int>()
        val pending = HashMap<RelPath, Int>()
        files.forEach { row ->
            row.path.ancestors.forEach { folder ->
                total.merge(folder, 1, Int::plus)
                if (row.status.needsSync) pending.merge(folder, 1, Int::plus)
            }
        }
        val folderRows =
            folders.map { folder ->
                val notSynced = pending[folder] ?: 0
                FileRow(
                    path = folder,
                    isFolder = true,
                    // A folder's own status is the sum of its files: see notSynced.
                    status = FileStatus.SYNCED,
                    notSynced = notSynced,
                    files = total[folder] ?: 0,
                )
            }
        val order = compareBy<FileRow>({ !it.isFolder }, { it.name.lowercase() }, { it.name })
        return (folderRows + files).groupBy { it.path.parent }.mapValues { (_, rows) -> rows.sortedWith(order) }
    }
}
