package io.github.drivesync.feature.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ActivityKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Filter chips of the activity log (FR-M2). */
enum class ActivityFilter(
    val kinds: Set<ActivityKind>?,
) {
    ALL(null),
    TRANSFERS(setOf(ActivityKind.UPLOADED, ActivityKind.DOWNLOADED, ActivityKind.MOVED, ActivityKind.FOLDER_CREATED)),
    DELETES(setOf(ActivityKind.TRASHED_ON_DRIVE, ActivityKind.RECYCLED_ON_PHONE)),
    CONFLICTS(setOf(ActivityKind.CONFLICT)),
    PROBLEMS(setOf(ActivityKind.FAILED, ActivityKind.PAUSED, ActivityKind.WAITING_FOR_CONFIRMATION)),
}

/** One log line, ready to show. */
data class ActivityRow(
    val id: Long,
    val kind: ActivityKind,
    val pairName: String,
    val path: String?,
    val detail: String?,
    val time: Long,
)

data class ActivityDay(
    val date: LocalDate,
    val rows: List<ActivityRow>,
)

data class ActivityUiState(
    val loading: Boolean = true,
    val filter: ActivityFilter = ActivityFilter.ALL,
    val days: List<ActivityDay> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

/** Newest first, grouped by local day; pure so it is testable without Android. */
fun activityDays(
    entries: List<ActivityEntity>,
    pairNames: Map<String, String>,
    filter: ActivityFilter,
    zone: ZoneId,
): List<ActivityDay> =
    entries
        .asSequence()
        .filter { filter.kinds == null || it.kind in filter.kinds }
        .sortedWith(compareByDescending<ActivityEntity> { it.time }.thenByDescending { it.id })
        .map { ActivityRow(it.id, it.kind, pairNames[it.pairId].orEmpty(), it.path, it.detail, it.time) }
        .groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
        .map { (date, rows) -> ActivityDay(date, rows) }
