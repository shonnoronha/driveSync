package io.github.drivesync.feature.pairs

import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.feature.pairs.detail.FileRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileRulesTest {
    @Test
    fun `patterns are one per line`() {
        val parsed = FileRules.parsePatterns(" *.tmp\n\nnode_modules \r\nCamera/old")
        assertEquals(listOf("*.tmp", "node_modules", "Camera/old"), parsed)
        assertEquals(emptyList<String>(), FileRules.parsePatterns("  \n "))
    }

    @Test
    fun `size labels`() {
        assertEquals("10 MB", FileRules.sizeLabel(10L * 1024 * 1024))
        assertEquals("4 GB", FileRules.sizeLabel(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `summary counts real patterns only`() {
        val summary = FileRules.summaryParts(FileFilters(listOf("*.tmp", "# a note", ""), 100L * 1024 * 1024, true))
        assertEquals(FileRules.Summary(patterns = 1, maxSize = "100 MB", hidden = true), summary)
        assertTrue(FileRules.summaryParts(FileFilters()).isEmpty)
    }
}
