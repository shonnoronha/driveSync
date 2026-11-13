package io.github.drivesync.core.sync.model

/** Per-pair sync direction (PRD FR-P3). */
enum class SyncDirection {
    TWO_WAY,
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
    ;

    companion object {
        val DEFAULT = TWO_WAY
    }
}

/** What to do when both sides changed the same file differently (PRD 3.5). */
enum class ConflictPolicy {
    KEEP_BOTH,

    /** The only place where timestamps of the two sides are compared; the UI must say so. */
    NEWEST_WINS,
    LOCAL_WINS,
    REMOTE_WINS,
    ASK,
    ;

    companion object {
        val DEFAULT = KEEP_BOTH
    }
}

/** Pause and ask when one run deletes more than [minFiles] files AND more than [minFraction] of the pair (FR-S6). */
data class MassDeleteGuard(
    val minFiles: Int = 20,
    val minFraction: Double = 0.10,
)

data class PairSettings(
    val direction: SyncDirection = SyncDirection.DEFAULT,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.DEFAULT,
    /** One-way pairs only: propagate deletes from the source side to the destination (PRD 3.9). */
    val mirrorDeletions: Boolean = false,
    val massDeleteGuard: MassDeleteGuard = MassDeleteGuard(),
)
