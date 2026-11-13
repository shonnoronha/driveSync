package io.github.drivesync.core.localstorage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TreeUrisTest {
    private val base = "content://com.android.externalstorage.documents/tree/"

    @Test
    fun `internal storage paths`() {
        assertEquals(TreeUris.Location("DCIM/Camera", true), TreeUris.location(base + "primary%3ADCIM%2FCamera"))
        assertEquals("Camera", TreeUris.folderName(base + "primary%3ADCIM%2FCamera"))
    }

    @Test
    fun `SD card paths`() {
        assertEquals(TreeUris.Location("Music", false), TreeUris.location(base + "1234-ABCD%3AMusic"))
    }

    @Test
    fun `unknown shapes`() {
        assertNull(TreeUris.location("content://x/document/y"))
        assertNull(TreeUris.location(base + "noColon"))
        assertNull(TreeUris.folderName(base + "primary%3A"))
        assertNull(TreeUris.folderName("content://x"))
    }
}
