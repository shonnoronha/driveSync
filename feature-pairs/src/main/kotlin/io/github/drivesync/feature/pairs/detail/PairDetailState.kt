package io.github.drivesync.feature.pairs.detail

import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.feature.activity.ActivityRow
import io.github.drivesync.feature.pairs.home.PairItem

data class PairDetailUiState(
    val loading: Boolean = true,
    val pair: PairItem? = null,
    val settings: PairSettings = PairSettings(),
    val enabled: Boolean = true,
    val recent: List<ActivityRow> = emptyList(),
    val skippedFiles: Int = 0,
    val now: Long = 0,
    /** Set once the pair was removed, so the screen closes. */
    val removed: Boolean = false,
    val error: String? = null,
)

/** Callbacks of the pair detail screen. */
data class PairDetailActions(
    val onBack: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onDirection: (SyncDirection) -> Unit = {},
    val onPolicy: (ConflictPolicy) -> Unit = {},
    val onMirror: (Boolean) -> Unit = {},
    val onConfirmDeletes: () -> Unit = {},
    val onRestoreInstead: () -> Unit = {},
    val onChooseFolderAgain: () -> Unit = {},
    val onRecreateDriveFolder: () -> Unit = {},
    val onRemove: (trashDriveFolder: Boolean) -> Unit = {},
    val onOpenActivity: () -> Unit = {},
)
