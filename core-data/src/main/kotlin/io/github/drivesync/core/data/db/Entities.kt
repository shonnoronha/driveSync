package io.github.drivesync.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SkipReason

/** One folder pair (PRD 3.1). */
@Entity(tableName = "pairs")
data class PairEntity(
    @PrimaryKey val id: String,
    val name: String,
    val localTreeUri: String,
    val driveFolderId: String,
    val direction: SyncDirection,
    val conflictPolicy: ConflictPolicy,
    val mirrorDeletions: Boolean,
    val massDeleteMinFiles: Int,
    val massDeleteMinFraction: Double,
    /** False when the user paused the pair. */
    val enabled: Boolean,
    /** False until the first base commit: no base means merge only, never delete (PRD 3.9 rule 7). */
    val hasBase: Boolean,
    val status: PairStatus,
    val statusDetail: String?,
    val lastSyncAt: Long?,
    val createdAt: Long,
)

enum class PairStatus {
    IDLE,

    /** Folder unreachable or suddenly empty, sign-in needed, Drive full: only the user can fix it. */
    NEEDS_ATTENTION,

    /** The mass-delete guard tripped; the user must confirm the deletes (FR-S6). */
    WAITING_FOR_CONFIRMATION,

    /** The last run finished, but some files failed; they are retried on the next run. */
    ERROR,
}

/** One row of a pair's base snapshot. */
@Entity(
    tableName = "base_entries",
    primaryKeys = ["pairId", "path"],
    foreignKeys = [ForeignKey(PairEntity::class, ["id"], ["pairId"], onDelete = ForeignKey.CASCADE)],
)
data class BaseEntryEntity(
    val pairId: String,
    val path: String,
    val isFolder: Boolean,
    val md5: String?,
    val localSize: Long,
    val localLastModified: Long,
    val remoteFileId: String?,
    val remoteVersion: Long,
)

/** Activity log (FR-M2). */
@Entity(
    tableName = "activity",
    indices = [Index("pairId", "time")],
    foreignKeys = [ForeignKey(PairEntity::class, ["id"], ["pairId"], onDelete = ForeignKey.CASCADE)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: String,
    val time: Long,
    val kind: ActivityKind,
    val path: String? = null,
    val detail: String? = null,
)

enum class ActivityKind {
    UPLOADED,
    DOWNLOADED,
    MOVED,
    FOLDER_CREATED,
    TRASHED_ON_DRIVE,
    RECYCLED_ON_PHONE,
    CONFLICT,
    FAILED,
    PAUSED,
    WAITING_FOR_CONFIRMATION,
}

/** Conflicts of the latest run (replaced every run), for the conflicts screen. */
@Entity(
    tableName = "conflicts",
    primaryKeys = ["pairId", "path"],
    foreignKeys = [ForeignKey(PairEntity::class, ["id"], ["pairId"], onDelete = ForeignKey.CASCADE)],
)
data class ConflictEntity(
    val pairId: String,
    val path: String,
    val kind: ConflictKind,
    val resolution: ConflictResolution,
    val conflictCopy: String?,
    val detectedAt: Long,
)

/** Files the latest run left alone (Google Docs, duplicates in upload-only pairs), replaced every run (FR-S12). */
@Entity(
    tableName = "skipped",
    primaryKeys = ["pairId", "path"],
    foreignKeys = [ForeignKey(PairEntity::class, ["id"], ["pairId"], onDelete = ForeignKey.CASCADE)],
)
data class SkippedEntity(
    val pairId: String,
    val path: String,
    val reason: SkipReason,
)
