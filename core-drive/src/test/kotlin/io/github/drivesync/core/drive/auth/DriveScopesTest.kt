package io.github.drivesync.core.drive.auth

import io.github.drivesync.core.sync.store.AccessMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveScopesTest {
    @Test
    fun `app folder asks for drive file only`() {
        assertEquals(listOf(DriveScopes.DRIVE_FILE), DriveScopes.scopesFor(AccessMode.APP_FOLDER))
    }

    @Test
    fun `full drive keeps drive file and adds drive`() {
        assertEquals(
            listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE),
            DriveScopes.scopesFor(AccessMode.FULL_DRIVE),
        )
        assertEquals("https://www.googleapis.com/auth/drive", DriveScopes.DRIVE)
    }

    @Test
    fun `sign-out revokes every scope`() {
        AccessMode.entries.forEach { mode -> assertTrue(DriveScopes.ALL.containsAll(DriveScopes.scopesFor(mode))) }
    }
}
