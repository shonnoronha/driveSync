package io.github.drivesync.feature.activity

import io.github.drivesync.core.data.db.ActivityEntity
import io.github.drivesync.core.data.db.ActivityKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class ActivityExportTest {
    private val header = ExportHeader("1.2 (5)", "15 (API 35)", "Google Pixel 8", ZoneOffset.UTC)
    private val pairs = mapOf("p" to "Holiday photos")
    private val entries =
        listOf(
            ActivityEntity(1, "p", 1_000, ActivityKind.UPLOADED, "Rome/IMG_0042.jpg"),
            ActivityEntity(2, "p", 2_000, ActivityKind.DOWNLOADED, "Rome/notes.txt"),
            ActivityEntity(
                3,
                "p",
                3_000,
                ActivityKind.FAILED,
                "Rome/IMG_0042.jpg",
                "IMG_0042.jpg in Holiday photos: no access for me@example.com",
            ),
            ActivityEntity(4, "gone", 4_000, ActivityKind.CONFLICT, "a.txt"),
        )

    private fun export(
        hide: Boolean,
        filter: ActivityFilter = ActivityFilter.ALL,
        rows: List<ActivityEntity> = entries,
    ) = activityLogText(rows, pairs, filter, hide, header)

    @Test
    fun `plain export, newest first`() {
        val lines = export(hide = false).lines()
        assertEquals("App 1.2 (5) · Android 15 (API 35) · Google Pixel 8", lines[1])
        assertEquals("Filter: all · entries: 4", lines[2])
        assertEquals("1970-01-01 00:00:04 +00:00 | CONFLICT | (removed folder) | a.txt", lines[4])
        assertEquals(
            "1970-01-01 00:00:01 +00:00 | UPLOADED | Holiday photos | Rome/IMG_0042.jpg",
            lines[7],
        )
    }

    @Test
    fun `hidden names stay consistent`() {
        val text = export(hide = true)
        listOf("Holiday", "Rome", "IMG_0042", "notes", "me@example.com").forEach {
            assertFalse(it in text, "$it leaked")
        }
        val lines = text.lines()
        assertTrue(lines[2].endsWith("names hidden"))
        assertEquals(
            "1970-01-01 00:00:03 +00:00 | FAILED | Folder 1 | name2/name3.jpg | " +
                "name3.jpg in Folder 1: no access for [email]",
            lines[5],
        )
        assertTrue(lines[6].endsWith("| Folder 1 | name2/name4.txt"))
        assertTrue(lines[4].endsWith("| (removed folder) | name1.txt"))
    }

    @Test
    fun `short names do not garble text`() {
        val row = ActivityEntity(1, "p", 1, ActivityKind.FAILED, "old/x.md", "folder old is gone")
        val line = export(hide = true, rows = listOf(row)).lines()[4]
        assertTrue(line.endsWith("| name1/name2.md | folder name1 is gone"), line)
    }

    @Test
    fun `follows the filter and caps the size`() {
        val problems = export(hide = false, filter = ActivityFilter.PROBLEMS)
        assertTrue("Filter: problems · entries: 1" in problems)
        val many = (1..MAX_EXPORT_ROWS + 5).map { ActivityEntity(it.toLong(), "p", it.toLong(), ActivityKind.UPLOADED) }
        val capped = export(hide = false, rows = many).lines()
        assertEquals("Filter: all · entries: newest 1000 of 1005", capped[2])
        assertEquals(MAX_EXPORT_ROWS, capped.count { "UPLOADED" in it })
    }
}
