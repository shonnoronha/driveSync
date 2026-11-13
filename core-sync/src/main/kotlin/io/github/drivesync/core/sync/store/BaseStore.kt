package io.github.drivesync.core.sync.store

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.RelPath

/** Persistent base snapshot of ONE pair (Room in production). */
interface BaseStore {
    /** Null until the first commit: "no base", so the planner merges and never deletes (PRD 3.9 rule 7). */
    suspend fun load(): BaseSnapshot?

    /**
     * Applies [updates] atomically and marks the base as existing. The executor calls it right after each
     * completed action, so the base always matches what was transferred (echo suppression, PRD 3.9 rule 2).
     */
    suspend fun commit(updates: List<BaseUpdate>)
}

sealed interface BaseUpdate {
    data class Put(
        val entry: BaseEntry,
    ) : BaseUpdate

    data class Remove(
        val path: RelPath,
    ) : BaseUpdate
}
