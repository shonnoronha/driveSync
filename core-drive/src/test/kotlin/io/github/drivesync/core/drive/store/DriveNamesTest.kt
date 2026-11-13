package io.github.drivesync.core.drive.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DriveNamesTest {
    @ParameterizedTest
    @CsvSource("plain.txt,plain.txt", "a/b,a_b", "/,_", "'',_", "'.','_.'", "..,_..", "...,...")
    fun `maps Drive names to path segments`(
        name: String,
        segment: String,
    ) {
        assertEquals(segment, DriveNames.toSegment(name))
    }

    @org.junit.jupiter.api.Test
    fun `only segments with the replacement character may be mapped`() {
        assertTrue(DriveNames.mayBeMapped("a_b"))
        assertFalse(DriveNames.mayBeMapped("ab"))
    }
}
