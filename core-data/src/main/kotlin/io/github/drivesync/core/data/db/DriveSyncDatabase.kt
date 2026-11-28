package io.github.drivesync.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PairEntity::class,
        BaseEntryEntity::class,
        ActivityEntity::class,
        ConflictEntity::class,
        SkippedEntity::class,
        ResolutionEntity::class,
        BaseHistoryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class DriveSyncDatabase : RoomDatabase() {
    abstract fun pairs(): PairDao

    abstract fun base(): BaseDao

    abstract fun activity(): ActivityDao

    abstract fun pairItems(): PairItemsDao

    abstract fun resolutions(): ResolutionDao

    companion object {
        const val NAME = "drivesync.db"

        /** v2: base rows remember the Drive name of a phone copy kept apart from a Drive name clash. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE base_entries ADD COLUMN remotePath TEXT")
                }
            }

        /** v3: per-pair file rules (FR-P5). Nullable, so existing pairs keep syncing everything. */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pairs ADD COLUMN excludePatterns TEXT")
                    db.execSQL("ALTER TABLE pairs ADD COLUMN maxFileSize INTEGER")
                    db.execSQL("ALTER TABLE pairs ADD COLUMN skipHidden INTEGER")
                }
            }

        /** v4: per-folder options (FR-P4): include subfolders, Wi-Fi only, charging only. Null keeps the defaults. */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pairs ADD COLUMN includeSubfolders INTEGER")
                    db.execSQL("ALTER TABLE pairs ADD COLUMN wifiOnly INTEGER")
                    db.execSQL("ALTER TABLE pairs ADD COLUMN chargingOnly INTEGER")
                }
            }

        /** v5: the base history behind Rewind. It starts empty; the first sync saves the current base as its start. */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `base_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`pairId` TEXT NOT NULL, `runAt` INTEGER NOT NULL, `path` TEXT NOT NULL, " +
                            "`present` INTEGER NOT NULL, `isFolder` INTEGER NOT NULL, `md5` TEXT, " +
                            "`localSize` INTEGER NOT NULL, `localLastModified` INTEGER NOT NULL, " +
                            "`remoteFileId` TEXT, `remoteVersion` INTEGER NOT NULL, `remotePath` TEXT, " +
                            "FOREIGN KEY(`pairId`) " +
                            "REFERENCES `pairs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_base_history_pairId_runAt` " +
                            "ON `base_history` (`pairId`, `runAt`)",
                    )
                }
            }
    }
}
