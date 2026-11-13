package io.github.drivesync.core.drive.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DriveQueryTest {
    @Test
    fun `escapes quotes and backslashes`() {
        assertEquals("""'it\'s \\ ok'""", DriveQuery.literal("""it's \ ok"""))
    }

    @Test
    fun `children of several folders`() {
        assertEquals("('a' in parents or 'b' in parents) and trashed = false", DriveQuery.childrenOf(listOf("a", "b")))
    }

    @Test
    fun `child by name`() {
        assertEquals("('a' in parents) and trashed = false and name = 'x'", DriveQuery.childNamed(listOf("a"), "x"))
    }

    @Test
    fun `needs at least one parent`() {
        assertThrows<IllegalArgumentException> { DriveQuery.childrenOf(emptyList()) }
    }

    @Test
    fun `folders by private tag`() {
        assertEquals(
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false and " +
                "appProperties has { key='role' and value='root' }",
            DriveQuery.foldersTagged("role", "root"),
        )
    }

    @Test
    fun `RFC 3339 round trip`() {
        assertEquals("2025-06-15T15:06:40Z", DriveClient.rfc3339(1_750_000_000_000L))
        assertEquals(1_750_000_000_000L, DriveClient.epochMillis("2025-06-15T15:06:40.000Z"))
        assertEquals(0L, DriveClient.epochMillis(null))
    }
}
