package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry

/**
 * Lazy hashing (PRD 3.9): a local file is hashed only when its MD5 can change the plan. The engine fills in
 * [LocalEntry.md5] for these paths before calling [SyncPlanner.plan]. Without a hash the planner stays safe:
 * it treats the file as modified, which at worst produces a keep-both conflict instead of a link.
 */
object HashRequirements {
    fun localPathsNeedingHash(
        local: List<LocalEntry>,
        remote: List<RemoteEntry>,
        base: BaseSnapshot?,
    ): Set<RelPath> {
        val baseByPath = base?.entries.orEmpty().associateBy { it.path }
        val remoteFiles = remote.filter { !it.isFolder }.mapTo(HashSet()) { it.path }
        val localPaths = local.mapTo(HashSet()) { it.path }
        val missingBaseSizes =
            baseByPath.values
                .filter { !it.isFolder && it.path !in localPaths }
                .mapTo(HashSet()) { it.localSize }

        return local
            .asSequence()
            .filter { !it.isFolder && it.md5 == null }
            .filter { entry ->
                val b = baseByPath[entry.path]
                when {
                    // Metadata changed: is it a real edit or only a touch?
                    b != null -> entry.size != b.localSize || entry.lastModified != b.localLastModified

                    // Both sides new at the same path: link or conflict.
                    entry.path in remoteFiles -> true

                    // Possible rename of a base file that disappeared.
                    else -> entry.size in missingBaseSizes
                }
            }.mapTo(HashSet()) { it.path }
    }
}
