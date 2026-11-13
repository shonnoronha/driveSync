package io.github.drivesync.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** App-wide settings (PRD 4.5) and account state. */
data class AppSettings(
    val accountEmail: String? = null,
    /** The tagged "Drive Sync" root, tracked by id (PRD 3.8). */
    val rootFolderId: String? = null,
    /** Periodic sync interval; WorkManager's minimum is 15 minutes (PRD 3.4). */
    val syncIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /** Used in conflict-copy names; null means the device model. */
    val deviceName: String? = null,
    val logRetentionDays: Int = DEFAULT_LOG_RETENTION_DAYS,
) {
    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 24 * 60
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val DEFAULT_LOG_RETENTION_DAYS = 30
    }
}

class SettingsRepository
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        val settings: Flow<AppSettings> =
            store.data.map { p ->
                AppSettings(
                    accountEmail = p[ACCOUNT],
                    rootFolderId = p[ROOT],
                    syncIntervalMinutes = p[INTERVAL] ?: AppSettings.DEFAULT_INTERVAL_MINUTES,
                    wifiOnly = p[WIFI_ONLY] ?: false,
                    chargingOnly = p[CHARGING_ONLY] ?: false,
                    deviceName = p[DEVICE_NAME],
                    logRetentionDays = p[LOG_RETENTION] ?: AppSettings.DEFAULT_LOG_RETENTION_DAYS,
                )
            }

        suspend fun current(): AppSettings = settings.first()

        suspend fun setAccount(
            email: String?,
            rootFolderId: String?,
        ) = store.edit {
            it.putOrRemove(ACCOUNT, email)
            it.putOrRemove(ROOT, rootFolderId)
        }

        suspend fun setSyncInterval(minutes: Int) =
            store.edit {
                it[INTERVAL] = minutes.coerceIn(AppSettings.MIN_INTERVAL_MINUTES, AppSettings.MAX_INTERVAL_MINUTES)
            }

        suspend fun setNetworkRules(
            wifiOnly: Boolean,
            chargingOnly: Boolean,
        ) = store.edit {
            it[WIFI_ONLY] = wifiOnly
            it[CHARGING_ONLY] = chargingOnly
        }

        suspend fun setDeviceName(name: String?) =
            store.edit { it.putOrRemove(DEVICE_NAME, name?.trim()?.ifEmpty { null }) }

        suspend fun setLogRetentionDays(days: Int) = store.edit { it[LOG_RETENTION] = days.coerceAtLeast(1) }

        /** FR-A5: sign out clears every setting. */
        suspend fun clear() = store.edit { it.clear() }

        private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
            key: Preferences.Key<String>,
            value: String?,
        ) {
            if (value == null) remove(key) else this[key] = value
        }

        private companion object {
            val ACCOUNT = stringPreferencesKey("account_email")
            val ROOT = stringPreferencesKey("root_folder_id")
            val INTERVAL = intPreferencesKey("sync_interval_minutes")
            val WIFI_ONLY = booleanPreferencesKey("wifi_only")
            val CHARGING_ONLY = booleanPreferencesKey("charging_only")
            val DEVICE_NAME = stringPreferencesKey("device_name")
            val LOG_RETENTION = intPreferencesKey("log_retention_days")
        }
    }
