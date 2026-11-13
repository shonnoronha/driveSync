package io.github.drivesync.screenshots

import androidx.compose.foundation.layout.padding
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.ui.theme.SeedColors
import io.github.drivesync.feature.pairs.home.HomeActions
import io.github.drivesync.feature.pairs.home.HomeScreen
import io.github.drivesync.feature.pairs.home.HomeUiState
import io.github.drivesync.feature.pairs.home.PairItem
import io.github.drivesync.feature.pairs.home.PairState
import org.junit.Test

class HomeScreenshots : Screenshots() {
    private val now = 1_763_000_000_000L
    private val minute = 60_000L

    private val pairs =
        listOf(
            PairItem(
                "1",
                "Camera",
                "DCIM/Camera",
                true,
                SyncDirection.UPLOAD_ONLY,
                PairState.SYNCING,
                null,
                now - 3 * minute,
            ),
            PairItem(
                "2",
                "Notes",
                "Documents/Notes",
                true,
                SyncDirection.TWO_WAY,
                PairState.UP_TO_DATE,
                null,
                now - 12 * minute,
            ),
            PairItem(
                "3",
                "Music",
                "Music",
                false,
                SyncDirection.TWO_WAY,
                PairState.NEEDS_ATTENTION,
                "The phone folder can't be read: SD card removed",
                now - 26 * 60 * minute,
            ),
            PairItem(
                "4",
                "Scans",
                "Download/Scans",
                true,
                SyncDirection.DOWNLOAD_ONLY,
                PairState.CONFIRM_DELETES,
                "30 of 120 files would be deleted",
                now - 40 * minute,
            ),
            PairItem("5", "Old phone", "Backup", true, SyncDirection.TWO_WAY, PairState.PAUSED, null, null),
        )

    @Test
    fun home() = snap("home") { HomeScreen(HomeUiState(false, pairs, conflictCount = 2, now = now), HomeActions()) }

    @Test
    fun homeDark() =
        snap("home_dark", dark = true) {
            HomeScreen(HomeUiState(false, pairs, conflictCount = 2, now = now), HomeActions())
        }

    @Test
    fun homeGreenAmoled() =
        snap("home_green_amoled", dark = true, seed = SeedColors.GREEN, amoled = true) {
            HomeScreen(HomeUiState(false, pairs, conflictCount = 2, now = now), HomeActions())
        }

    /** The app shell: home above the expressive bottom navigation bar. */
    @Test
    fun shell() =
        snap("shell") {
            androidx.compose.material3.Scaffold(
                bottomBar = { io.github.drivesync.BottomBar(io.github.drivesync.Routes.HOME, pendingConflicts = 3) {} },
            ) { padding ->
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .padding(padding),
                ) {
                    HomeScreen(HomeUiState(false, pairs, conflictCount = 3, now = now), HomeActions())
                }
            }
        }

    @Test
    fun homeEmpty() = snap("home_empty") { HomeScreen(HomeUiState(loading = false), HomeActions()) }
}
