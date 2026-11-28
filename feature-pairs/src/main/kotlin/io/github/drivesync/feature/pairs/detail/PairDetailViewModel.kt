package io.github.drivesync.feature.pairs.detail

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.drive.auth.google.AuthorizationStep
import io.github.drivesync.core.drive.auth.google.GoogleAccount
import io.github.drivesync.core.drive.auth.google.GoogleAuth
import io.github.drivesync.core.drive.folders.DriveSyncFolders
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.work.GoogleAccess
import io.github.drivesync.core.work.android.SyncScheduler
import io.github.drivesync.feature.activity.ActivityRow
import io.github.drivesync.feature.pairs.add.AddPairRules
import io.github.drivesync.feature.pairs.home.homeState
import io.github.drivesync.feature.pairs.home.runningSyncs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class PairDetailViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val pairs: PairRepository,
        activity: ActivityRepository,
        private val settings: SettingsRepository,
        private val folders: DriveSyncFolders,
        private val trees: SafTrees,
        private val scheduler: SyncScheduler,
        workManager: WorkManager,
        private val auth: GoogleAuth,
    ) : ViewModel() {
        private val pairId: String = checkNotNull(savedState[PAIR_ID]) { "pairId missing" }
        private val local = MutableStateFlow(LocalState())

        /** A Google consent screen for the route to launch (granting access again, FR-A4). */
        val consentRequests = MutableStateFlow<IntentSender?>(null)

        private data class LocalState(
            val removed: Boolean = false,
            val error: String? = null,
        )

        val state: StateFlow<PairDetailUiState> =
            combine(
                pairs.observePairs().map { list -> list.firstOrNull { it.id == pairId } },
                activity.observe(pairId, RECENT),
                activity.observeSkipped(pairId),
                local,
                workManager.runningSyncs(),
            ) { pair, recent, skipped, extra, running ->
                if (pair == null) {
                    PairDetailUiState(loading = false, removed = true)
                } else {
                    val now = System.currentTimeMillis()
                    PairDetailUiState(
                        loading = false,
                        pair =
                            homeState(
                                listOf(pair),
                                0,
                                running.pairIds,
                                running.all,
                                now,
                                running.progress,
                            ).pairs.single(),
                        settings = pair.settings,
                        enabled = pair.enabled,
                        recent = recent.map { ActivityRow(it.id, it.kind, pair.name, it.path, it.detail, it.time) },
                        skippedFiles = skipped.size,
                        now = now,
                        removed = extra.removed,
                        error = extra.error,
                        accessLost = !trees.isPersisted(pair.localTreeUri),
                        googleAccessNeeded =
                            pair.status == PairStatus.NEEDS_ATTENTION && GoogleAccess.isNeeded(pair.statusDetail),
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PairDetailUiState())

        fun syncNow() = scheduler.syncNow(pairId)

        fun confirmDeletes() = scheduler.syncNow(pairId, deletionsConfirmed = true)

        fun restoreInstead() =
            launch {
                pairs.restoreInsteadOfDelete(pairId)
                scheduler.syncNow(pairId)
            }

        fun setEnabled(enabled: Boolean) = launch { pairs.setEnabled(pairId, enabled) }

        fun setDirection(direction: SyncDirection) =
            update {
                it.copy(
                    direction = direction,
                    mirrorDeletions =
                        it.mirrorDeletions && AddPairRules.mirrorAllowed(direction),
                )
            }

        /**
         * Conflict policy, mirror deletions and file rules (FR-P5) apply from the next run. A new file rule never
         * deletes anything: files that already synced stay on both sides.
         */
        fun edit(change: (PairSettings) -> PairSettings) = update(change)

        /** After access was lost: the user picked the folder again. The next run merges, so nothing is deleted. */
        fun changeFolder(treeUri: String?) {
            if (treeUri == null) return
            launch {
                val old = pair()?.localTreeUri
                trees.persist(treeUri)
                if (old != null && old != treeUri) runCatching { trees.release(old) }
                pairs.changeLocalFolder(pairId, treeUri)
                scheduler.syncNow(pairId)
            }
        }

        /** The Drive folder was deleted or trashed: make a new one; the next run re-uploads (PRD 3.8). */
        fun recreateDriveFolder() =
            launch {
                val pair = pair() ?: return@launch
                val root = settings.current().rootFolderId ?: throw IOException("Not signed in")
                val folder = folders.createPairFolder(root, pair.name, pair.id)
                pairs.relink(pairId, folder.folderId)
                scheduler.syncNow(pairId)
            }

        /**
         * Google access lapsed or was revoked: ask again with the saved access mode (full Drive access stays on),
         * then sync every folder, since they all paused for the same reason.
         */
        fun grantGoogleAccess() =
            launch {
                val email = settings.current().accountEmail ?: throw IOException("Not signed in")
                when (val step = googleStep { auth.authorize(GoogleAccount(email, null)) }) {
                    AuthorizationStep.Authorized -> scheduler.syncNow()
                    is AuthorizationStep.NeedsConsent -> consentRequests.value = step.intent.intentSender
                }
            }

        /** Result of the consent screen; null data means the user backed out. */
        fun onConsentResult(data: Intent?) {
            consentRequests.value = null
            launch {
                when (googleStep { auth.completeAuthorization(data) }) {
                    AuthorizationStep.Authorized -> scheduler.syncNow()
                    is AuthorizationStep.NeedsConsent -> throw IOException("Google access wasn't granted")
                }
            }
        }

        /** Never touches phone files; the Drive folder only goes to the trash if asked (PRD 3.8). */
        fun remove(trashDriveFolder: Boolean) =
            launch {
                val pair = pair() ?: return@launch
                if (trashDriveFolder) folders.trashFolder(pair.driveFolderId)
                runCatching { trees.release(pair.localTreeUri) }
                pairs.remove(pairId)
                local.value = local.value.copy(removed = true)
            }

        private suspend fun pair(): SyncPair? = pairs.pair(pairId)

        private fun update(change: (PairSettings) -> PairSettings) =
            launch { pair()?.let { pairs.updateSettings(pairId, change(it.settings)) } }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    local.value = local.value.copy(error = null)
                    block()
                } catch (e: IOException) {
                    local.value = local.value.copy(error = e.message ?: "Something went wrong")
                }
            }
        }

        companion object {
            const val PAIR_ID = "pairId"
            private const val RECENT = 10
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** Play services reports refusals and a closed consent screen as exceptions; the screen shows them as an error. */
private suspend fun googleStep(block: suspend () -> AuthorizationStep): AuthorizationStep =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        throw IOException("Google access wasn't granted", e)
    }
