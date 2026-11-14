package io.github.drivesync.feature.pairs.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.work.android.SyncScheduler
import io.github.drivesync.feature.pairs.detail.PairDetailViewModel
import io.github.drivesync.feature.pairs.home.runningSyncs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Files screen of one pair: scans the phone folder and compares it with the base saved by the last sync.
 * Rescans when a sync finishes, while one makes progress, and on demand.
 */
@HiltViewModel
class FilesViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val pairs: PairRepository,
        private val activity: ActivityRepository,
        private val trees: SafTrees,
        private val scheduler: SyncScheduler,
        workManager: WorkManager,
    ) : ViewModel() {
        private val pairId: String = checkNotNull(savedState[PairDetailViewModel.PAIR_ID]) { "pairId missing" }
        private val folder = MutableStateFlow(savedState.get<String>(KEY_FOLDER)?.let(RelPath::of))
        private val onlyNotSynced = MutableStateFlow(savedState[KEY_ONLY_NOT_SYNCED] ?: false)
        private val refresh = MutableStateFlow(0)

        private val pair: Flow<SyncPair?> = pairs.observePairs().map { list -> list.firstOrNull { it.id == pairId } }

        /** Whether a sync of this pair runs, and how many files it has done; a change triggers a rescan. */
        private val syncing: Flow<SyncActivity> =
            workManager
                .runningSyncs()
                .map { running ->
                    val mine = pairId in running.pairIds || running.all
                    SyncActivity(mine, running.progress[pairId]?.done)
                }.distinctUntilChanged()

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        private val index: Flow<Loaded> =
            combine(
                pair.filterNotNull().map { it.localTreeUri to it.lastSyncAt }.distinctUntilChanged(),
                syncing,
                activity.observeConflicts().map { all -> all.filter { it.pairId == pairId } },
                activity.observeSkipped(pairId),
                refresh,
            ) { (treeUri, lastSyncAt), _, conflicts, skipped, _ ->
                Inputs(
                    treeUri = treeUri,
                    lastSyncAt = lastSyncAt,
                    pendingConflicts =
                        conflicts.filter { it.resolution == ConflictResolution.PENDING }.mapTo(HashSet()) { it.path },
                    skipped = skipped.mapTo(HashSet()) { it.path },
                )
            }.debounce(RESCAN_DEBOUNCE_MILLIS)
                .mapLatest(::load)

        val state: StateFlow<FilesUiState> =
            combine(pair, index.onStart<Loaded?> { emit(null) }, folder, onlyNotSynced, syncing) {
                pair,
                loaded,
                folder,
                only,
                syncing,
                ->
                FilesUiState(
                    loading = loaded == null,
                    pairName = pair?.name.orEmpty(),
                    lastSyncAt = pair?.lastSyncAt,
                    folder = folder,
                    onlyNotSynced = only,
                    syncing = syncing.running,
                    index = loaded?.index ?: FileIndex.EMPTY,
                    unavailable = loaded?.unavailable,
                    now = System.currentTimeMillis(),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FilesUiState())

        fun open(row: FileRow) {
            if (row.isFolder) setFolder(row.path)
        }

        /** Goes up one folder; false at the pair root, where back should leave the screen. */
        fun up(): Boolean {
            val current = folder.value ?: return false
            setFolder(current.parent)
            return true
        }

        fun setOnlyNotSynced(only: Boolean) {
            onlyNotSynced.value = only
            savedState[KEY_ONLY_NOT_SYNCED] = only
        }

        fun syncNow() {
            scheduler.syncNow(pairId)
        }

        fun rescan() {
            refresh.value++
        }

        private fun setFolder(path: RelPath?) {
            folder.value = path
            savedState[KEY_FOLDER] = path?.value
        }

        private suspend fun load(inputs: Inputs): Loaded =
            when (val scan = trees.store(inputs.treeUri).scan()) {
                is Snapshot.Unavailable -> {
                    Loaded(FileIndex.EMPTY, scan.reason)
                }

                is Snapshot.Available -> {
                    val failed = inputs.lastSyncAt?.let { activity.failedFiles(pairId, it) }.orEmpty()
                    val issues = RunIssues(inputs.pendingConflicts, failed, inputs.skipped)
                    Loaded(FileStatusRules.index(scan.entries, pairs.baseEntries(pairId), issues), null)
                }
            }

        private data class SyncActivity(
            val running: Boolean,
            val done: Int?,
        )

        private data class Inputs(
            val treeUri: String,
            val lastSyncAt: Long?,
            val pendingConflicts: Set<String>,
            val skipped: Set<String>,
        )

        private data class Loaded(
            val index: FileIndex,
            val unavailable: String?,
        )

        private companion object {
            const val KEY_FOLDER = "folder"
            const val KEY_ONLY_NOT_SYNCED = "onlyNotSynced"
            const val RESCAN_DEBOUNCE_MILLIS = 500L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

data class FilesUiState(
    val loading: Boolean = true,
    val pairName: String = "",
    val lastSyncAt: Long? = null,
    /** The folder being shown; null is the pair root. */
    val folder: RelPath? = null,
    val onlyNotSynced: Boolean = false,
    val syncing: Boolean = false,
    val index: FileIndex = FileIndex.EMPTY,
    /** Why the phone folder could not be read, if it could not. */
    val unavailable: String? = null,
    val now: Long = 0,
) {
    val rows: List<FileRow> get() = if (onlyNotSynced) index.notSyncedIn(folder) else index.rowsIn(folder)
}
