package io.github.drivesync.feature.settings.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BytesTest {
    @Test
    fun `small sizes are whole bytes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun `one decimal below 100`() {
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.3 GB", formatBytes(2_469_606_195))
    }

    @Test
    fun `whole numbers drop the decimal`() {
        assertEquals("15 GB", formatBytes(16_106_127_360))
        assertEquals("1 MB", formatBytes(1_048_576))
        assertEquals("200 GB", formatBytes(214_748_364_800))
    }
}
