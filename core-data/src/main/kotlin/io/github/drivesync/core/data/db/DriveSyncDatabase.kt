package io.github.drivesync.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PairEntity::class,
        BaseEntryEntity::class,
        ActivityEntity::class,
        ConflictEntity::class,
        SkippedEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DriveSyncDatabase : RoomDatabase() {
    abstract fun pairs(): PairDao

    abstract fun base(): BaseDao

    abstract fun activity(): ActivityDao

    abstract fun pairItems(): PairItemsDao

    companion object {
        const val NAME = "drivesync.db"
    }
}
