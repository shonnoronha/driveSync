package io.github.drivesync.feature.pairs

import io.github.drivesync.core.drive.folders.PairFolder
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.feature.pairs.add.AddPairRules
import io.github.drivesync.feature.pairs.add.AddPairUiState
import io.github.drivesync.feature.pairs.add.AddStep
import io.github.drivesync.feature.pairs.add.ExistingPair
import io.github.drivesync.feature.pairs.add.FirstSyncPreview
import io.github.drivesync.feature.pairs.add.FolderProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddPairRulesTest {
    private val base = "content://com.android.externalstorage.documents/tree/"
    private val camera = ExistingPair("Camera", base + "primary%3ADCIM%2FCamera")

    @Test
    fun `nested, parent and same folders are blocked`() {
        listOf("primary%3ADCIM", "primary%3ADCIM%2FCamera", "primary%3ADCIM%2FCamera%2F2026").forEach {
            val problems = AddPairRules.folderProblems(base + it, listOf(camera), grantsInUse = 1)
            assertEquals(listOf(FolderProblem.Overlaps("Camera")), problems, it)
        }
    }

    @Test
    fun `siblings, other volumes and other providers`() {
        assertEquals(
            emptyList<FolderProblem>(),
            AddPairRules.folderProblems(base + "primary%3ADCIM%2FOther", listOf(camera), 1),
        )
        assertEquals(
            emptyList<FolderProblem>(),
            AddPairRules.folderProblems(base + "1234-ABCD%3ADCIM", listOf(camera), 1),
        )
        val cloud = AddPairRules.folderProblems("content://com.cloud.provider/tree/x", listOf(camera), 1)
        assertEquals(listOf(FolderProblem.UnsupportedProvider), cloud)
        assertFalse(cloud.single().blocking)
    }

    @Test
    fun `same uri is blocked even when unparseable`() {
        val odd = ExistingPair("Odd", "content://com.cloud.provider/tree/x")
        assertTrue(AddPairRules.folderProblems(odd.treeUri, listOf(odd), 1).any { it.blocking })
    }

    @Test
    fun `warns near the grant limit`() {
        val problems = AddPairRules.folderProblems(base + "primary%3ANotes", emptyList(), grantsInUse = 120)
        assertEquals(listOf(FolderProblem.GrantLimitNear(120)), problems)
    }

    @Test
    fun `linkable folders exclude those in use`() {
        val a = PairFolder("a", "A", "p1")
        val b = PairFolder("b", "B", null)
        assertEquals(listOf(b), AddPairRules.linkable(listOf(a, b), setOf("a")))
    }

    @Test
    fun `steps and continue rules`() {
        assertEquals(AddStep.NAME, AddPairRules.next(AddStep.FOLDER))
        assertNull(AddPairRules.next(AddStep.REVIEW))
        assertNull(AddPairRules.previous(AddStep.FOLDER))
        assertFalse(AddPairUiState().canContinue)
        assertTrue(AddPairUiState(treeUri = "x").canContinue)
        assertFalse(AddPairUiState(treeUri = "x", problems = listOf(FolderProblem.Overlaps("A"))).canContinue)
        assertFalse(AddPairUiState(step = AddStep.NAME, name = " ").canContinue)
        assertTrue(AddPairUiState(step = AddStep.OPTIONS).canContinue)
        assertFalse(AddPairUiState(step = AddStep.REVIEW).canContinue)
        assertTrue(AddPairUiState(step = AddStep.REVIEW, preview = FirstSyncPreview(1, null)).canContinue)
        assertFalse(
            AddPairUiState(step = AddStep.REVIEW, preview = FirstSyncPreview(1, null), working = true).canContinue,
        )
        assertFalse(AddPairRules.mirrorAllowed(SyncDirection.TWO_WAY))
        assertTrue(AddPairRules.mirrorAllowed(SyncDirection.UPLOAD_ONLY))
    }
}
