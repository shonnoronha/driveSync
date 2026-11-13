package io.github.drivesync.feature.pairs

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.feature.pairs.home.PairState
import io.github.drivesync.feature.pairs.home.homeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HomeStateTest {
    private fun pair(
        id: String,
        status: PairStatus = PairStatus.IDLE,
        enabled: Boolean = true,
        uri: String = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
    ) = SyncPair(id, "Camera", uri, "f", PairSettings(), enabled, status, "detail", 5L)

    @Test
    fun `maps paths and states`() {
        val pairs =
            listOf(
                pair("ok"),
                pair("syncing"),
                pair("attention", PairStatus.NEEDS_ATTENTION),
                pair("confirm", PairStatus.WAITING_FOR_CONFIRMATION),
                pair("errors", PairStatus.ERROR),
                pair("paused", enabled = false),
                pair("sd", uri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A"),
                pair("odd", uri = "content://other/x"),
            )
        val state =
            homeState(pairs, conflictCount = 2, syncingPairs = setOf("syncing", "paused"), syncingAll = false, now = 9)
        assertFalse(state.loading)
        assertEquals(2, state.conflictCount)
        assertEquals(
            listOf(
                PairState.UP_TO_DATE,
                PairState.SYNCING,
                PairState.NEEDS_ATTENTION,
                PairState.CONFIRM_DELETES,
                PairState.ERRORS,
                PairState.PAUSED,
                PairState.UP_TO_DATE,
                PairState.UP_TO_DATE,
            ),
            state.pairs.map { it.state },
        )
        assertEquals("DCIM/Camera", state.pairs[0].phonePath)
        assertEquals("/", state.pairs[6].phonePath)
        assertFalse(state.pairs[6].onInternalStorage)
        assertEquals("content://other/x", state.pairs[7].phonePath)
    }

    @Test
    fun `an all-pairs run marks every enabled pair`() {
        val state = homeState(listOf(pair("a"), pair("b", enabled = false)), 0, emptySet(), syncingAll = true, now = 0)
        assertEquals(listOf(PairState.SYNCING, PairState.PAUSED), state.pairs.map { it.state })
    }
}
