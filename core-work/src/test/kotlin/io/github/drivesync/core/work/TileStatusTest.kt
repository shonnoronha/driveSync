package io.github.drivesync.core.work

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.sync.model.PairSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStatusTest {
    private fun pair(
        status: PairStatus = PairStatus.IDLE,
        enabled: Boolean = true,
        lastSync: Long? = 100,
    ) = SyncPair("id", "Camera", "content://tree", "drive", PairSettings(), enabled, status, null, lastSync)

    @Test
    fun `set up first`() {
        assertEquals(TileStatus.SET_UP, TileStatus.of(signedIn = false, listOf(pair()), syncing = true))
        assertEquals(TileStatus.SET_UP, TileStatus.of(signedIn = true, emptyList(), syncing = false))
        assertFalse(TileStatus.SET_UP.tapSyncs)
    }

    @Test
    fun `order of the states`() {
        val stuck = pair(PairStatus.WAITING_FOR_CONFIRMATION)
        assertEquals(TileStatus.SYNCING, TileStatus.of(true, listOf(stuck), syncing = true))
        assertEquals(TileStatus.NEEDS_YOU, TileStatus.of(true, listOf(stuck, pair(PairStatus.ERROR)), false))
        assertEquals(TileStatus.FAILED, TileStatus.of(true, listOf(pair(), pair(PairStatus.ERROR)), false))
        assertEquals(TileStatus.IDLE, TileStatus.of(true, listOf(pair()), false))
        assertTrue(TileStatus.FAILED.tapSyncs)
        assertFalse(TileStatus.NEEDS_YOU.tapSyncs)
    }

    @Test
    fun `paused folders are ignored`() {
        val paused = pair(PairStatus.NEEDS_ATTENTION, enabled = false)
        assertEquals(TileStatus.PAUSED, TileStatus.of(true, listOf(paused), false))
        assertEquals(TileStatus.IDLE, TileStatus.of(true, listOf(paused, pair()), false))
    }

    @Test
    fun `synced since the oldest folder`() {
        assertEquals(50L, TileStatus.allSyncedSince(listOf(pair(lastSync = 50), pair(lastSync = 90))))
        assertNull(TileStatus.allSyncedSince(listOf(pair(lastSync = 50), pair(lastSync = null))))
        assertEquals(
            90L,
            TileStatus.allSyncedSince(listOf(pair(lastSync = null, enabled = false), pair(lastSync = 90))),
        )
        assertNull(TileStatus.allSyncedSince(emptyList()))
    }
}
