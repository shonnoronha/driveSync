package io.github.drivesync.feature.pairs.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.drive.folders.DriveSyncFolders
import io.github.drivesync.core.drive.store.DriveRemoteStoreFactory
import io.github.drivesync.core.localstorage.TreeUris
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.work.android.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AddPairViewModel
    @Inject
    constructor(
        private val pairs: PairRepository,
        private val settings: SettingsRepository,
        private val trees: SafTrees,
        private val folders: DriveSyncFolders,
        private val drive: DriveRemoteStoreFactory,
        private val scheduler: SyncScheduler,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(AddPairUiState())
        val state: StateFlow<AddPairUiState> = mutable.asStateFlow()

        init {
            viewModelScope.launch { loadLinkable() }
        }

        fun onFolderPicked(treeUri: String?) {
            if (treeUri == null) return
            viewModelScope.launch {
                val existing = pairs.pairs().map { ExistingPair(it.name, it.localTreeUri) }
                val location = TreeUris.location(treeUri)
                mutable.update {
                    it.copy(
                        treeUri = treeUri,
                        folderPath = location?.path ?: treeUri,
                        onInternalStorage = location?.onInternalStorage ?: true,
                        problems = AddPairRules.folderProblems(treeUri, existing, trees.persistedCount()),
                        name = it.name.ifBlank { TreeUris.folderName(treeUri).orEmpty() },
                    )
                }
            }
        }

        fun onName(name: String) = mutable.update { it.copy(name = name) }

        fun onTarget(target: DriveTarget) =
            mutable.update {
                val name = if (target is DriveTarget.Existing && it.name.isBlank()) target.folder.name else it.name
                it.copy(target = target, name = name)
            }

        fun onDirection(direction: SyncDirection) =
            mutable.update {
                it.copy(
                    direction = direction,
                    mirrorDeletions =
                        it.mirrorDeletions && AddPairRules.mirrorAllowed(direction),
                )
            }

        fun onPolicy(policy: ConflictPolicy) = mutable.update { it.copy(conflictPolicy = policy) }

        fun onMirror(mirror: Boolean) = mutable.update { it.copy(mirrorDeletions = mirror) }

        fun back() = mutable.update { s -> AddPairRules.previous(s.step)?.let { s.copy(step = it, error = null) } ?: s }

        fun next() {
            val current = mutable.value
            if (!current.canContinue) return
            if (current.step == AddStep.REVIEW) {
                create()
                return
            }
            val next = AddPairRules.next(current.step) ?: return
            mutable.update { it.copy(step = next, preview = if (next == AddStep.REVIEW) null else it.preview) }
            if (next == AddStep.REVIEW) viewModelScope.launch { countFiles() }
        }

        private suspend fun loadLinkable() {
            val rootId = settings.current().rootFolderId ?: return
            try {
                val used = pairs.pairs().map { it.driveFolderId }.toSet()
                mutable.update { it.copy(linkableFolders = AddPairRules.linkable(folders.pairFolders(rootId), used)) }
            } catch (_: IOException) {
                // Offline: the user can still create a new folder.
            }
        }

        private suspend fun countFiles() {
            val s = mutable.value
            val uri = s.treeUri ?: return
            val phone = trees.store(uri).scan().fileCount()
            val target = s.target
            val driveFiles =
                if (target is DriveTarget.Existing) {
                    try {
                        drive.create(target.folder.folderId).scan().fileCount()
                    } catch (_: IOException) {
                        0
                    }
                } else {
                    null
                }
            mutable.update { it.copy(preview = FirstSyncPreview(phone, driveFiles)) }
        }

        private fun create() {
            val s = mutable.value
            val uri = s.treeUri ?: return
            mutable.update { it.copy(working = true, error = null) }
            viewModelScope.launch {
                try {
                    trees.persist(uri)
                    val name = s.name.trim()
                    val (pairId, folderId) =
                        when (val target = s.target) {
                            is DriveTarget.Existing -> {
                                (target.folder.pairId ?: pairs.newPairId()) to
                                    target.folder.folderId
                            }

                            DriveTarget.New -> {
                                val id = pairs.newPairId()
                                val root = settings.current().rootFolderId ?: throw IOException("Not signed in")
                                id to folders.createPairFolder(root, name, id).folderId
                            }
                        }
                    val pairSettings = PairSettings(s.direction, s.conflictPolicy, s.mirrorDeletions)
                    pairs.add(pairId, name, uri, folderId, pairSettings)
                    scheduler.syncNow(pairId)
                    mutable.update { it.copy(working = false, createdPairId = pairId) }
                } catch (e: IOException) {
                    trees.release(uri)
                    mutable.update {
                        it.copy(
                            working = false,
                            error = e.message ?: "Could not create the Drive folder",
                        )
                    }
                }
            }
        }

        private fun Snapshot<*>.fileCount(): Int =
            (this as? Snapshot.Available)?.entries?.count { entry ->
                when (entry) {
                    is io.github.drivesync.core.sync.model.LocalEntry -> {
                        !entry.isFolder &&
                            !IgnoreRules.isIgnored(entry.path)
                    }

                    is io.github.drivesync.core.sync.model.RemoteEntry -> {
                        !entry.isFolder && !entry.isGoogleNative
                    }

                    else -> {
                        false
                    }
                }
            } ?: 0
    }
