package io.github.drivesync.core.sync.model

/**
 * One change of a pair's base, in the order it happened. [entry] is the state after the change; null means the path
 * left the base. [runAt] groups the changes of one run (sync or rewind).
 */
data class BaseChange(
    val runAt: Long,
    val path: RelPath,
    val entry: BaseEntry?,
)

/**
 * A pair's synced history (Rewind): the base, run by run. The base is what both sides agreed on, so the state as of
 * a run is exactly what the folder looked like, on both sides, right after that run. The first point may be the
 * base as it was when history started.
 */
class BaseTimeline(
    changes: List<BaseChange>,
) {
    /** Stable, so the order within one run is kept. */
    private val changes: List<BaseChange> = changes.sortedBy { it.runAt }

    /** The points the user can go back to, oldest first. */
    val points: List<Long> = this.changes.map { it.runAt }.distinct()

    val isEmpty: Boolean get() = points.isEmpty()

    /** The base right after the run at [runAt] (and every earlier one). */
    fun stateAsOf(runAt: Long): Map<RelPath, BaseEntry> = replay { it.runAt <= runAt }

    /** The base just before the run at [runAt]: what "undo this sync" goes back to. */
    fun stateBefore(runAt: Long): Map<RelPath, BaseEntry> = replay { it.runAt < runAt }

    fun changesIn(runAt: Long): List<BaseChange> = changes.filter { it.runAt == runAt }

    /** The latest point at or before [time], e.g. the run an activity entry belongs to. */
    fun pointAtOrBefore(time: Long): Long? = points.lastOrNull { it <= time }

    private inline fun replay(include: (BaseChange) -> Boolean): Map<RelPath, BaseEntry> {
        val state = LinkedHashMap<RelPath, BaseEntry>()
        for (change in changes) {
            if (!include(change)) break
            val entry = change.entry
            if (entry == null) state.remove(change.path) else state[change.path] = entry
        }
        return state
    }
}
