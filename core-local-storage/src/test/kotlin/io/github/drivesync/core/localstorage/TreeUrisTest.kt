package io.github.drivesync.core.localstorage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TreeUrisTest {
    private val base = "content://com.android.externalstorage.documents/tree/"

    @Test
    fun `internal storage paths`() {
        val camera = TreeUris.location(base + "primary%3ADCIM%2FCamera")!!
        assertEquals(TreeUris.Location("DCIM/Camera", "primary"), camera)
        assertEquals(true, camera.onInternalStorage)
        assertEquals("Camera", TreeUris.folderName(base + "primary%3ADCIM%2FCamera"))
    }

    @Test
    fun `SD card paths`() {
        val music = TreeUris.location(base + "1234-ABCD%3AMusic")!!
        assertEquals(false, music.onInternalStorage)
    }

    @Test
    fun `nested and equal folders overlap`() {
        val dcim = TreeUris.Location("DCIM", "primary")
        assertEquals(true, dcim.overlaps(TreeUris.Location("DCIM/Camera", "primary")))
        assertEquals(true, TreeUris.Location("DCIM/Camera", "primary").overlaps(dcim))
        assertEquals(true, dcim.overlaps(TreeUris.Location("DCIM/", "primary")))
        assertEquals(true, dcim.overlaps(TreeUris.Location("", "primary")))
        assertEquals(false, dcim.overlaps(TreeUris.Location("DCIM2", "primary")))
        assertEquals(false, dcim.overlaps(TreeUris.Location("DCIM", "1234-ABCD")))
    }

    @Test
    fun `unknown shapes`() {
        assertNull(TreeUris.location("content://x/document/y"))
        assertNull(TreeUris.location(base + "noColon"))
        assertNull(TreeUris.folderName(base + "primary%3A"))
        assertNull(TreeUris.folderName("content://x"))
    }
}
