package io.github.drivesync.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PairDao {
    @Query("SELECT * FROM pairs ORDER BY createdAt")
    fun observeAll(): Flow<List<PairEntity>>

    @Query("SELECT * FROM pairs ORDER BY createdAt")
    suspend fun all(): List<PairEntity>

    @Query("SELECT * FROM pairs WHERE id = :id")
    suspend fun get(id: String): PairEntity?

    @Upsert
    suspend fun upsert(pair: PairEntity)

    @Query("DELETE FROM pairs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pairs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    @Query("UPDATE pairs SET status = :status, statusDetail = :detail WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: PairStatus,
        detail: String?,
    )

    @Query("UPDATE pairs SET lastSyncAt = :time WHERE id = :id")
    suspend fun setLastSync(
        id: String,
        time: Long,
    )
}

@Dao
interface BaseDao {
    @Query("SELECT * FROM base_entries WHERE pairId = :pairId ORDER BY path")
    suspend fun entries(pairId: String): List<BaseEntryEntity>

    @Upsert
    suspend fun upsert(entries: List<BaseEntryEntity>)

    @Query("DELETE FROM base_entries WHERE pairId = :pairId AND path IN (:paths)")
    suspend fun delete(
        pairId: String,
        paths: List<String>,
    )

    @Query("UPDATE pairs SET hasBase = 1 WHERE id = :pairId")
    suspend fun markBaseExists(pairId: String)

    @Query("SELECT hasBase FROM pairs WHERE id = :pairId")
    suspend fun hasBase(pairId: String): Boolean?

    /** One transaction per executor action, so the base never disagrees with what was transferred (rule 2). */
    @Transaction
    suspend fun apply(
        pairId: String,
        puts: List<BaseEntryEntity>,
        removes: List<String>,
    ) {
        if (removes.isNotEmpty()) delete(pairId, removes)
        if (puts.isNotEmpty()) upsert(puts)
        markBaseExists(pairId)
    }

    /** Forgets the base, e.g. when a pair is re-linked: the next run merges without deleting (rule 7). */
    @Transaction
    suspend fun reset(pairId: String) {
        clear(pairId)
        clearFlag(pairId)
    }

    @Query("DELETE FROM base_entries WHERE pairId = :pairId")
    suspend fun clear(pairId: String)

    @Query("UPDATE pairs SET hasBase = 0 WHERE id = :pairId")
    suspend fun clearFlag(pairId: String)
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insert(entries: List<ActivityEntity>)

    @Query("SELECT * FROM activity ORDER BY time DESC, id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity WHERE pairId = :pairId ORDER BY time DESC, id DESC LIMIT :limit")
    fun observePair(
        pairId: String,
        limit: Int,
    ): Flow<List<ActivityEntity>>

    @Query("DELETE FROM activity WHERE time < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface PairItemsDao {
    @Query("SELECT * FROM conflicts ORDER BY pairId, path")
    fun observeConflicts(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM skipped WHERE pairId = :pairId ORDER BY path")
    fun observeSkipped(pairId: String): Flow<List<SkippedEntity>>

    @Transaction
    suspend fun replace(
        pairId: String,
        conflicts: List<ConflictEntity>,
        skipped: List<SkippedEntity>,
    ) {
        deleteConflicts(pairId)
        deleteSkipped(pairId)
        insertConflicts(conflicts)
        insertSkipped(skipped)
    }

    @Insert
    suspend fun insertConflicts(conflicts: List<ConflictEntity>)

    @Insert
    suspend fun insertSkipped(skipped: List<SkippedEntity>)

    @Query("DELETE FROM conflicts WHERE pairId = :pairId")
    suspend fun deleteConflicts(pairId: String)

    @Query("DELETE FROM skipped WHERE pairId = :pairId")
    suspend fun deleteSkipped(pairId: String)
}
