package io.github.drivesync.core.sync.fakes

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.store.BaseStore
import io.github.drivesync.core.sync.store.BaseUpdate

/** Base store kept in memory. [commits] counts calls so tests can check per-action checkpointing. */
class InMemoryBaseStore : BaseStore {
    private var entries: MutableMap<RelPath, BaseEntry>? = null

    var commits: Int = 0
        private set

    /** Throw from this to simulate a failed DB write. */
    var beforeCommit: ((List<BaseUpdate>) -> Unit)? = null

    override suspend fun load(): BaseSnapshot? = entries?.let { BaseSnapshot(it.values.sortedBy { e -> e.path }) }

    override suspend fun commit(updates: List<BaseUpdate>) {
        beforeCommit?.invoke(updates)
        val map = entries ?: mutableMapOf<RelPath, BaseEntry>().also { entries = it }
        for (u in updates) {
            when (u) {
                is BaseUpdate.Put -> map[u.entry.path] = u.entry
                is BaseUpdate.Remove -> map.remove(u.path)
            }
        }
        commits++
    }

    /** Simulates cleared app data / reinstall: no base at all. */
    fun clear() {
        entries = null
    }

    fun entry(path: String): BaseEntry? = entries?.get(RelPath.of(path))

    fun paths(): Set<String> =
        entries
            ?.keys
            ?.map { it.value }
            ?.toSet()
            .orEmpty()
}
