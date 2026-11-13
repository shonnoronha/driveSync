package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.ConflictNamer
import io.github.drivesync.core.sync.plan.ConflictStamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDateTime

class ConflictNamerTest {
    private fun namer(
        device: String = "Pixel",
        taken: Set<String> = emptySet(),
    ) = ConflictNamer(
        ConflictStamp(LocalDateTime.of(2026, 11, 12, 14, 30, 59), device),
        taken.mapTo(HashSet(), RelPath::of),
    )

    @ParameterizedTest
    @CsvSource(
        "report.docx, report (conflict 2026-11-12 14-30 Pixel).docx",
        "Makefile, Makefile (conflict 2026-11-12 14-30 Pixel)",
        ".bashrc, .bashrc (conflict 2026-11-12 14-30 Pixel)",
        "archive.tar.gz, archive.tar (conflict 2026-11-12 14-30 Pixel).gz",
        "Docs/2026/report.docx, Docs/2026/report (conflict 2026-11-12 14-30 Pixel).docx",
        "trailing., trailing (conflict 2026-11-12 14-30 Pixel).",
    )
    fun `builds the PRD 3_5 name`(
        original: String,
        expected: String,
    ) {
        assertEquals(RelPath.of(expected), namer().conflictPath(RelPath.of(original)))
    }

    @Test
    fun `uses 24h time and drops seconds`() {
        val n = ConflictNamer(ConflictStamp(LocalDateTime.of(2026, 1, 2, 3, 4, 5), "Pixel"), emptySet())
        assertEquals(RelPath.of("a (conflict 2026-01-02 03-04 Pixel).txt"), n.conflictPath(RelPath.of("a.txt")))
    }

    @Test
    fun `adds a counter when the name is taken`() {
        val n = namer(taken = setOf("a (conflict 2026-11-12 14-30 Pixel).txt"))
        assertEquals(RelPath.of("a (conflict 2026-11-12 14-30 Pixel 2).txt"), n.conflictPath(RelPath.of("a.txt")))
    }

    @Test
    fun `never hands out the same name twice`() {
        val n = namer(taken = setOf("a (conflict 2026-11-12 14-30 Pixel 2).txt"))
        val names = List(3) { n.conflictPath(RelPath.of("a.txt")).value }
        assertEquals(
            listOf(
                "a (conflict 2026-11-12 14-30 Pixel).txt",
                "a (conflict 2026-11-12 14-30 Pixel 3).txt",
                "a (conflict 2026-11-12 14-30 Pixel 4).txt",
            ),
            names,
        )
    }

    @Test
    fun `the same name in another folder is not a clash`() {
        val n = namer(taken = setOf("x/a (conflict 2026-11-12 14-30 Pixel).txt"))
        assertEquals(RelPath.of("y/a (conflict 2026-11-12 14-30 Pixel).txt"), n.conflictPath(RelPath.of("y/a.txt")))
    }

    @ParameterizedTest
    @CsvSource(
        "'My/Phone:1', My_Phone_1",
        "'a\\b*c?d', a_b_c_d",
        "'<x>|\"y\"', _x___y_",
        "'  Pixel 9  ', Pixel 9",
        "'', device",
        "'   ', device",
    )
    fun `sanitizes the device name`(
        device: String,
        expected: String,
    ) {
        assertEquals(
            RelPath.of("a (conflict 2026-11-12 14-30 $expected).txt"),
            namer(device = device).conflictPath(RelPath.of("a.txt")),
        )
    }

    @Test
    fun `replaces control characters in the device name`() {
        assertEquals(
            RelPath.of("a (conflict 2026-11-12 14-30 Pi_xel).txt"),
            namer(device = "Pi\nxel").conflictPath(RelPath.of("a.txt")),
        )
    }
}
