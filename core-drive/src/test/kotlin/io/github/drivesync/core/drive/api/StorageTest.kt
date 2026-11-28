package io.github.drivesync.core.drive.api

import io.github.drivesync.core.drive.FakeDriveServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageTest {
    private val drive = FakeDriveServer()

    @AfterEach
    fun tearDown() = drive.close()

    @Test
    fun `reads used and limit`() =
        runTest {
            val storage = drive.client().storage()
            assertEquals(DriveStorage(usedBytes = 1_073_741_824, limitBytes = 16_106_127_360), storage)
            assertFalse(storage.nearlyFull)
        }

    @Test
    fun `no limit means unlimited`() =
        runTest {
            drive.about = DriveAbout(DriveAbout.StorageQuota(limit = null, usage = "5"))
            val storage = drive.client().storage()
            assertNull(storage.limitBytes)
            assertNull(storage.fractionUsed)
            assertFalse(storage.nearlyFull)
        }

    @Test
    fun `nearly full from 90 percent`() {
        assertFalse(DriveStorage(89, 100).nearlyFull)
        assertTrue(DriveStorage(90, 100).nearlyFull)
        assertEquals(1f, DriveStorage(120, 100).fractionUsed)
    }
}
