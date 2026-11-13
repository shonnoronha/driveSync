package io.github.drivesync.feature.conflicts

import io.github.drivesync.core.data.db.ConflictEntity
import io.github.drivesync.core.data.db.ResolutionEntity
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution

data class ConflictItem(
    val pairId: String,
    val pairName: String,
    val path: String,
    val kind: ConflictKind,
    val resolution: ConflictResolution,
    val conflictCopy: String?,
    /** The user's answer waiting for the next sync, if any. */
    val chosen: ConflictPolicy?,
) {
    /** The answers that make sense for this kind of conflict. */
    val choices: List<ConflictPolicy>
        get() =
            when (kind) {
                ConflictKind.BOTH_MODIFIED, ConflictKind.BOTH_NEW, ConflictKind.DESTINATION_MODIFIED -> {
                    listOf(ConflictPolicy.LOCAL_WINS, ConflictPolicy.REMOTE_WINS, ConflictPolicy.KEEP_BOTH)
                }

                // Modify/delete: the planner only keeps the changed file (PRD 3.3).
                ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED -> {
                    listOf(ConflictPolicy.KEEP_BOTH)
                }

                // Only the user can fix these, outside the app.
                ConflictKind.TYPE_MISMATCH, ConflictKind.DUPLICATE_REMOTE_NAME -> {
                    emptyList()
                }
            }
}

data class ConflictsUiState(
    val loading: Boolean = true,
    val pending: List<ConflictItem> = emptyList(),
    val resolved: List<ConflictItem> = emptyList(),
)

/** Callbacks of the conflicts screen. */
data class ConflictsActions(
    val onChoose: (ConflictItem, ConflictPolicy) -> Unit = { _, _ -> },
)

fun conflictsState(
    conflicts: List<ConflictEntity>,
    resolutions: List<ResolutionEntity>,
    pairNames: Map<String, String>,
): ConflictsUiState {
    val chosen = resolutions.associate { (it.pairId to it.path) to it.policy }
    val items =
        conflicts.map {
            ConflictItem(
                it.pairId,
                pairNames[it.pairId].orEmpty(),
                it.path,
                it.kind,
                it.resolution,
                it.conflictCopy,
                chosen[
                    it.pairId to
                        it.path,
                ],
            )
        }
    val (pending, resolved) = items.partition { it.resolution == ConflictResolution.PENDING }
    return ConflictsUiState(loading = false, pending = pending, resolved = resolved)
}
