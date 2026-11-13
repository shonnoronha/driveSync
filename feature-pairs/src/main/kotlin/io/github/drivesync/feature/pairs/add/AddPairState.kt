package io.github.drivesync.feature.pairs.add

import io.github.drivesync.core.drive.folders.PairFolder
import io.github.drivesync.core.localstorage.TreeUris
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection

enum class AddStep { FOLDER, NAME, OPTIONS, REVIEW }

/** Something about the picked folder the user should know. Blocking problems stop the wizard. */
sealed interface FolderProblem {
    val blocking: Boolean

    /** The folder is, contains or is inside an existing pair: syncing both would loop (FR-P7). */
    data class Overlaps(
        val pairName: String,
    ) : FolderProblem {
        override val blocking = true
    }

    /** Not on-device shared storage; v1 supports it only with a warning (PRD 6.2). */
    data object UnsupportedProvider : FolderProblem {
        override val blocking = false
    }

    /** Android allows only so many folder grants per app (PRD 6.2). */
    data class GrantLimitNear(
        val used: Int,
    ) : FolderProblem {
        override val blocking = false
    }
}

/** Where the pair lives on Drive: a new subfolder of Drive Sync, or an existing one (PRD 3.10 "link"). */
sealed interface DriveTarget {
    data object New : DriveTarget

    data class Existing(
        val folder: PairFolder,
    ) : DriveTarget
}

data class FirstSyncPreview(
    val phoneFiles: Int,
    /** Null for a new Drive folder, which starts empty. */
    val driveFiles: Int?,
)

data class AddPairUiState(
    val step: AddStep = AddStep.FOLDER,
    val treeUri: String? = null,
    val folderPath: String? = null,
    val onInternalStorage: Boolean = true,
    val problems: List<FolderProblem> = emptyList(),
    val name: String = "",
    val target: DriveTarget = DriveTarget.New,
    val linkableFolders: List<PairFolder> = emptyList(),
    val direction: SyncDirection = SyncDirection.DEFAULT,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.DEFAULT,
    val mirrorDeletions: Boolean = false,
    val preview: FirstSyncPreview? = null,
    val working: Boolean = false,
    val error: String? = null,
    val createdPairId: String? = null,
) {
    val canContinue: Boolean
        get() =
            !working &&
                when (step) {
                    AddStep.FOLDER -> treeUri != null && problems.none { it.blocking }
                    AddStep.NAME -> name.isNotBlank()
                    AddStep.OPTIONS -> true
                    AddStep.REVIEW -> preview != null
                }
}

/** An existing pair, for the overlap check. */
data class ExistingPair(
    val name: String,
    val treeUri: String,
)

object AddPairRules {
    /** Warn once a user has used this many persisted grants (Android allows 128 on old versions, 512 on new). */
    const val GRANT_WARNING_THRESHOLD = 100
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun folderProblems(
        treeUri: String,
        existing: List<ExistingPair>,
        grantsInUse: Int,
    ): List<FolderProblem> {
        val location = TreeUris.location(treeUri)
        val overlap =
            existing.firstOrNull { pair ->
                pair.treeUri == treeUri ||
                    (location != null && TreeUris.location(pair.treeUri)?.overlaps(location) == true)
            }
        return listOfNotNull(
            overlap?.let { FolderProblem.Overlaps(it.name) },
            FolderProblem.UnsupportedProvider.takeUnless { treeUri.contains("://$EXTERNAL_STORAGE_AUTHORITY/") },
            FolderProblem.GrantLimitNear(grantsInUse).takeIf { grantsInUse >= GRANT_WARNING_THRESHOLD },
        )
    }

    /** Drive folders that can be linked: tagged pair folders no current pair uses. */
    fun linkable(
        folders: List<PairFolder>,
        usedFolderIds: Set<String>,
    ): List<PairFolder> = folders.filter { it.folderId !in usedFolderIds }

    /** Mirror deletions only means something for one-way pairs (PRD 3.9). */
    fun mirrorAllowed(direction: SyncDirection): Boolean = direction != SyncDirection.TWO_WAY

    fun next(step: AddStep): AddStep? = AddStep.entries.getOrNull(step.ordinal + 1)

    fun previous(step: AddStep): AddStep? = AddStep.entries.getOrNull(step.ordinal - 1)
}

/** Callbacks of the add-folder wizard. */
data class AddPairActions(
    val onClose: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onTarget: (DriveTarget) -> Unit = {},
    val onDirection: (SyncDirection) -> Unit = {},
    val onPolicy: (ConflictPolicy) -> Unit = {},
    val onMirror: (Boolean) -> Unit = {},
)
