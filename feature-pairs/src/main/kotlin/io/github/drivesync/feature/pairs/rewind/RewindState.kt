package io.github.drivesync.feature.pairs.rewind

import io.github.drivesync.core.sync.model.BaseTimeline
import io.github.drivesync.core.sync.plan.RewindChange
import io.github.drivesync.core.sync.plan.RewindChangeKind
import io.github.drivesync.core.sync.plan.RewindPlanner
import io.github.drivesync.core.sync.plan.RewindRefusal
import io.github.drivesync.core.work.RewindResult

/** One point of a folder's history: right after a sync (or rewind) that changed [changes] files or folders. */
data class RewindPoint(
    val time: Long,
    val changes: Int,
)

/** What the Rewind screen shows: the points, the chosen one, and what going back there changes. */
data class RewindUiState(
    val loading: Boolean = true,
    val pairName: String = "",
    val points: List<RewindPoint> = emptyList(),
    /** Index into [points]; the last point is "now". */
    val selected: Int = 0,
    val changes: List<RewindChange> = emptyList(),
    val refusal: RewindRefusal? = null,
    val running: Boolean = false,
    /** How the last rewind of this folder went, while WorkManager still remembers it. */
    val result: RewindResult? = null,
) {
    val selectedPoint: RewindPoint? get() = points.getOrNull(selected)
    val isNow: Boolean get() = selected == points.lastIndex
    val canRewind: Boolean get() = !running && !isNow && changes.isNotEmpty() && refusal == null

    fun files(kind: RewindChangeKind): Int = changes.count { it.kind == kind && !it.isFolder }
}

/**
 * Pure, so it is testable without Android. The latest point is the base right now, so the preview needs no disk or
 * network. With nothing chosen yet, the point before the last sync is shown: "undo the last sync".
 */
fun rewindState(
    pairName: String,
    timeline: BaseTimeline,
    selectedTime: Long?,
    running: Boolean,
    result: RewindResult?,
): RewindUiState {
    val points = timeline.points.map { RewindPoint(it, timeline.changesIn(it).size) }
    if (points.isEmpty()) return RewindUiState(loading = false, pairName = pairName, running = running, result = result)
    val chosen = selectedTime?.let { time -> points.indexOfFirst { it.time == time } }?.takeIf { it >= 0 }
    val index = chosen ?: (points.lastIndex - 1).coerceAtLeast(0)
    val now = timeline.stateAsOf(points.last().time)
    val target = timeline.stateAsOf(points[index].time)
    return RewindUiState(
        loading = false,
        pairName = pairName,
        points = points,
        selected = index,
        changes = RewindPlanner.preview(target, now),
        refusal = RewindPlanner.refusal(target, now),
        running = running,
        result = result,
    )
}
