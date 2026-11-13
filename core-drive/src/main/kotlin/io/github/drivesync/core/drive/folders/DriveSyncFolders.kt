package io.github.drivesync.core.drive.folders

import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.api.DriveFile
import io.github.drivesync.core.drive.api.DriveQuery
import javax.inject.Inject

/**
 * The "Drive Sync" root and its per-pair subfolders (PRD 3.8, 3.10). Folders are found by private
 * `appProperties` tags and tracked by id, so the user may rename or move them, and a reinstall or a second phone
 * reuses the existing root instead of creating another one.
 */
class DriveSyncFolders
    @Inject
    constructor(
        private val client: DriveClient,
    ) {
        /**
         * The existing tagged root, or a new one in My Drive. Call on first sign-in only: once the app knows the
         * root id it must use [status] and never silently re-create a trashed root (PRD 3.8 recovery).
         * If two phones raced and created two roots, every phone picks the same one: the oldest.
         */
        suspend fun findOrCreateRoot(): String = findRoot()?.id ?: createRoot().id

        suspend fun findRoot(): DriveFile? =
            client
                .listAll(DriveQuery.foldersTagged(KEY_ROLE, ROLE_ROOT))
                .minWithOrNull(compareBy<DriveFile> { DriveClient.epochMillis(it.createdTime) }.thenBy { it.id })

        suspend fun createRoot(): DriveFile = client.createFolder(ROOT_NAME, MY_DRIVE, mapOf(KEY_ROLE to ROLE_ROOT))

        /** Whether a tracked root or pair folder still exists (PRD 3.8: recovery asks the user, never auto-fixes). */
        suspend fun status(folderId: String): FolderStatus {
            val folder = client.getOrNull(folderId)
            return when {
                folder == null || !folder.isFolder -> FolderStatus.MISSING
                folder.trashed -> FolderStatus.TRASHED
                else -> FolderStatus.OK
            }
        }

        /** Tagged pair folders inside the root, for "link to an existing Drive Sync subfolder" (PRD 3.10). */
        suspend fun pairFolders(rootId: String): List<PairFolder> =
            client
                .listAll(DriveQuery.childrenOf(listOf(rootId)))
                .filter { it.isFolder && it.appProperties[KEY_ROLE] == ROLE_PAIR }
                .map { PairFolder(it.id, it.name, it.appProperties[KEY_PAIR_ID]) }
                .sortedBy { it.name }

        /**
         * Creates the subfolder for a new pair, named after the local folder. Drive allows duplicate names, but
         * the app appends " (2)", " (3)", ... so the user can tell them apart (PRD 3.8).
         */
        suspend fun createPairFolder(
            rootId: String,
            localFolderName: String,
            pairId: String,
        ): PairFolder {
            val taken = client.listAll(DriveQuery.childrenOf(listOf(rootId))).mapTo(mutableSetOf()) { it.name }
            val name = uniqueName(localFolderName.ifBlank { DEFAULT_PAIR_NAME }, taken)
            val folder = client.createFolder(name, rootId, mapOf(KEY_ROLE to ROLE_PAIR, KEY_PAIR_ID to pairId))
            return PairFolder(folder.id, folder.name, pairId)
        }

        companion object {
            const val ROOT_NAME = "Drive Sync"
            const val KEY_ROLE = "role"
            const val KEY_PAIR_ID = "pairId"
            const val ROLE_ROOT = "root"
            const val ROLE_PAIR = "pair"
            private const val MY_DRIVE = "root"
            private const val DEFAULT_PAIR_NAME = "Folder"

            internal fun uniqueName(
                wanted: String,
                taken: Set<String>,
            ): String =
                generateSequence(1) { it + 1 }
                    .map { if (it == 1) wanted else "$wanted ($it)" }
                    .first { it !in taken }
        }
    }

enum class FolderStatus { OK, TRASHED, MISSING }

/** [pairId] is null for a folder whose tag was lost; it can still be linked. */
data class PairFolder(
    val folderId: String,
    val name: String,
    val pairId: String?,
)
