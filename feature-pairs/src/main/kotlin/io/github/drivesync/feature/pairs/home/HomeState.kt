package io.github.drivesync.feature.pairs.home

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.localstorage.TreeUris
import io.github.drivesync.core.sync.model.SyncDirection

/** Callbacks of the home screen, grouped to keep signatures short. */
data class HomeActions(
    val onAddPair: () -> Unit = {},
    val onOpenPair: (String) -> Unit = {},
    val onSyncAll: () -> Unit = {},
    val onSyncPair: (String) -> Unit = {},
    val onOpenConflicts: () -> Unit = {},
    val onOpenActivity: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

data class HomeUiState(
    val loading: Boolean = true,
    val pairs: List<PairItem> = emptyList(),
    val conflictCount: Int = 0,
    /** Epoch millis that "synced 5 min ago" is measured from. */
    val now: Long = 0,
)

data class PairItem(
    val id: String,
    val name: String,
    val phonePath: String,
    val onInternalStorage: Boolean,
    val direction: SyncDirection,
    val state: PairState,
    val detail: String?,
    val lastSyncAt: Long?,
)

/** What the pair card shows, folding the stored status, the user's pause and a running sync together. */
enum class PairState { UP_TO_DATE, SYNCING, NEEDS_ATTENTION, CONFIRM_DELETES, ERRORS, PAUSED }

/** Pure mapping from repository data to the screen state, so it is testable without Android. */
fun homeState(
    pairs: List<SyncPair>,
    conflictCount: Int,
    syncingPairs: Set<String>,
    syncingAll: Boolean,
    now: Long,
): HomeUiState =
    HomeUiState(
        loading = false,
        pairs =
            pairs.map { pair ->
                val location = TreeUris.location(pair.localTreeUri)
                PairItem(
                    id = pair.id,
                    name = pair.name,
                    phonePath = location?.path?.ifEmpty { "/" } ?: pair.localTreeUri,
                    onInternalStorage = location?.onInternalStorage ?: true,
                    direction = pair.settings.direction,
                    state = pair.state(syncing = pair.enabled && (syncingAll || pair.id in syncingPairs)),
                    detail = pair.statusDetail,
                    lastSyncAt = pair.lastSyncAt,
                )
            },
        conflictCount = conflictCount,
        now = now,
    )

private fun SyncPair.state(syncing: Boolean): PairState =
    when {
        !enabled -> PairState.PAUSED
        status == PairStatus.NEEDS_ATTENTION -> PairState.NEEDS_ATTENTION
        status == PairStatus.WAITING_FOR_CONFIRMATION -> PairState.CONFIRM_DELETES
        syncing -> PairState.SYNCING
        status == PairStatus.ERROR -> PairState.ERRORS
        else -> PairState.UP_TO_DATE
    }
