package io.github.drivesync.core.data

import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Sign-out (FR-A5): deletes everything the app stored. Files on the phone and in Drive are never touched. */
class AppDataCleaner
    @Inject
    constructor(
        private val db: DriveSyncDatabase,
        private val settings: SettingsRepository,
    ) {
        suspend fun clearAll() {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            settings.clear()
        }
    }
