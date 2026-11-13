package io.github.drivesync.screenshots

import io.github.drivesync.core.data.db.ActivityKind
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.feature.activity.ActivityDay
import io.github.drivesync.feature.activity.ActivityRow
import io.github.drivesync.feature.activity.ActivityScreen
import io.github.drivesync.feature.activity.ActivityUiState
import io.github.drivesync.feature.conflicts.ConflictItem
import io.github.drivesync.feature.conflicts.ConflictsActions
import io.github.drivesync.feature.conflicts.ConflictsScreen
import io.github.drivesync.feature.conflicts.ConflictsUiState
import io.github.drivesync.feature.pairs.detail.PairDetailActions
import io.github.drivesync.feature.pairs.detail.PairDetailScreen
import io.github.drivesync.feature.pairs.detail.PairDetailUiState
import io.github.drivesync.feature.pairs.home.PairItem
import io.github.drivesync.feature.pairs.home.PairState
import org.junit.Test
import java.time.LocalDate

class DetailScreenshots : Screenshots() {
    private val today = LocalDate.of(2026, 11, 13)
    private val t = 1_763_030_000_000L
    private val hour = 3_600_000L

    private val rows =
        listOf(
            ActivityRow(1, ActivityKind.UPLOADED, "Notes", "2026/meeting notes.md", null, t),
            ActivityRow(2, ActivityKind.DOWNLOADED, "Notes", "recipes/pasta.md", null, t - hour),
            ActivityRow(
                3,
                ActivityKind.CONFLICT,
                "Notes",
                "todo.md",
                "Both versions kept; the other one is todo (conflict 2026-11-13 09-30 Pixel).md",
                t - 2 * hour,
            ),
            ActivityRow(4, ActivityKind.MOVED, "Camera", "2026/IMG_2041.jpg", "from IMG_2041.jpg", t - 3 * hour),
            ActivityRow(5, ActivityKind.TRASHED_ON_DRIVE, "Notes", "old/draft.md", null, t - 4 * hour),
            ActivityRow(6, ActivityKind.FAILED, "Camera", "VID_0412.mp4", "Network unreachable", t - 5 * hour),
        )

    @Test
    fun activity() =
        snap("activity") {
            ActivityScreen(
                ActivityUiState(
                    false,
                    days = listOf(ActivityDay(today, rows.take(4)), ActivityDay(today.minusDays(1), rows.drop(4))),
                    today = today,
                ),
                {},
            )
        }

    @Test
    fun conflicts() =
        snap("conflicts") {
            ConflictsScreen(
                ConflictsUiState(
                    loading = false,
                    pending =
                        listOf(
                            ConflictItem(
                                "p",
                                "Notes",
                                "budget.xlsx",
                                ConflictKind.BOTH_MODIFIED,
                                ConflictResolution.PENDING,
                                null,
                                null,
                            ),
                            ConflictItem(
                                "p",
                                "Notes",
                                "plan.md",
                                ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED,
                                ConflictResolution.PENDING,
                                null,
                                ConflictPolicy.KEEP_BOTH,
                            ),
                            ConflictItem(
                                "q",
                                "Camera",
                                "2026",
                                ConflictKind.TYPE_MISMATCH,
                                ConflictResolution.PENDING,
                                null,
                                null,
                            ),
                        ),
                    resolved =
                        listOf(
                            ConflictItem(
                                "p",
                                "Notes",
                                "todo.md",
                                ConflictKind.BOTH_MODIFIED,
                                ConflictResolution.KEPT_BOTH,
                                "todo (conflict 2026-11-13 09-30 Pixel).md",
                                null,
                            ),
                        ),
                ),
                ConflictsActions(),
            )
        }

    private fun detail(
        state: PairState,
        detail: String?,
    ) = PairDetailUiState(
        loading = false,
        pair = PairItem("p", "Notes", "Documents/Notes", true, SyncDirection.UPLOAD_ONLY, state, detail, t - hour),
        settings = PairSettings(direction = SyncDirection.UPLOAD_ONLY),
        recent = rows.take(3),
        skippedFiles = 2,
        now = t,
    )

    @Test
    fun pairDetail() = snap("detail") { PairDetailScreen(detail(PairState.UP_TO_DATE, null), PairDetailActions()) }

    @Test
    fun pairDetailAttention() =
        snap("detail_attention") {
            PairDetailScreen(
                detail(PairState.NEEDS_ATTENTION, "The Drive folder can't be reached: it is in the trash"),
                PairDetailActions(),
            )
        }

    @Test
    fun pairDetailConfirm() =
        snap("detail_confirm_deletes", dark = true) {
            PairDetailScreen(detail(PairState.CONFIRM_DELETES, "30 of 120 files would be deleted"), PairDetailActions())
        }
}
