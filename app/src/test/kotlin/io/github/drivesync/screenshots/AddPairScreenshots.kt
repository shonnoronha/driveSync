package io.github.drivesync.screenshots

import io.github.drivesync.core.drive.folders.PairFolder
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.feature.pairs.add.AddPairActions
import io.github.drivesync.feature.pairs.add.AddPairScreen
import io.github.drivesync.feature.pairs.add.AddPairUiState
import io.github.drivesync.feature.pairs.add.AddStep
import io.github.drivesync.feature.pairs.add.DriveTarget
import io.github.drivesync.feature.pairs.add.FirstSyncPreview
import io.github.drivesync.feature.pairs.add.FolderProblem
import org.junit.Test

class AddPairScreenshots : Screenshots() {
    private val picked =
        AddPairUiState(treeUri = "content://x", folderPath = "Documents/Notes", name = "Notes")

    private fun shot(
        name: String,
        state: AddPairUiState,
    ) = snap(name) { AddPairScreen(state, AddPairActions()) }

    @Test
    fun folderEmpty() = shot("add_1_folder", AddPairUiState())

    @Test
    fun folderOverlap() =
        shot(
            "add_1_folder_overlap",
            picked.copy(folderPath = "DCIM", problems = listOf(FolderProblem.Overlaps("Camera"))),
        )

    @Test
    fun name() =
        shot(
            "add_2_name",
            picked.copy(
                step = AddStep.NAME,
                linkableFolders = listOf(PairFolder("f1", "Notes", "p1"), PairFolder("f2", "Old laptop", null)),
            ),
        )

    @Test
    fun options() = shot("add_3_options", picked.copy(step = AddStep.OPTIONS, direction = SyncDirection.UPLOAD_ONLY))

    @Test
    fun review() =
        shot(
            "add_4_review",
            picked.copy(
                step = AddStep.REVIEW,
                target = DriveTarget.Existing(PairFolder("f1", "Notes", "p1")),
                preview = FirstSyncPreview(phoneFiles = 214, driveFiles = 198),
            ),
        )
}
