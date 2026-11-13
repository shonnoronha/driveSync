package io.github.drivesync.feature.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ActivityKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ActivityDaysTest {
    private val day = 86_400_000L
    private val entries =
        listOf(
            ActivityEntity(1, "p", 10, ActivityKind.UPLOADED, "a"),
            ActivityEntity(2, "p", day + 5, ActivityKind.TRASHED_ON_DRIVE, "b"),
            ActivityEntity(3, "q", day + 9, ActivityKind.FAILED, "c", "disk"),
            ActivityEntity(4, "gone", day + 9, ActivityKind.CONFLICT, "d"),
        )

    @Test
    fun `newest first, grouped by day, with pair names`() {
        val days = activityDays(entries, mapOf("p" to "Notes", "q" to "Camera"), ActivityFilter.ALL, ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(1970, 1, 2), LocalDate.of(1970, 1, 1)), days.map { it.date })
        assertEquals(listOf(4L, 3L, 2L), days[0].rows.map { it.id })
        assertEquals(listOf("", "Camera", "Notes"), days[0].rows.map { it.pairName })
    }

    @Test
    fun `filters by kind`() {
        val problems = activityDays(entries, emptyMap(), ActivityFilter.PROBLEMS, ZoneOffset.UTC)
        assertEquals(listOf(3L), problems.flatMap { d -> d.rows.map { it.id } })
        assertEquals(1, activityDays(entries, emptyMap(), ActivityFilter.DELETES, ZoneOffset.UTC).single().rows.size)
        assertEquals(
            emptyList<ActivityDay>(),
            activityDays(emptyList(), emptyMap(), ActivityFilter.ALL, ZoneOffset.UTC),
        )
    }
}
