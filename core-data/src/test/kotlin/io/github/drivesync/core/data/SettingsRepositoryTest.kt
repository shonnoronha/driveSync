package io.github.drivesync.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.core.sync.store.AccessMode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Plain JVM (JUnit 5): DataStore needs no Android. */
class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private fun TestScope.repo() =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "s.preferences_pb") })

    @Test
    fun `defaults`() =
        runTest {
            assertEquals(AppSettings(), repo().current())
        }

    @Test
    fun `stores and clears every setting`() =
        runTest {
            val repo = repo()
            repo.setAccount("me@example.com", "root-id")
            repo.setSyncInterval(60)
            repo.setNetworkRules(wifiOnly = true, chargingOnly = true)
            repo.setDeviceName("  Pixel 9 ")
            repo.setLogRetentionDays(7)
            repo.setTheme(ThemeMode.DARK, dynamicColor = false)
            repo.setAccent(0xFF00FF00.toInt(), amoled = true)
            repo.setAccessMode(AccessMode.FULL_DRIVE)
            assertEquals(
                AppSettings(
                    "me@example.com",
                    "root-id",
                    60,
                    true,
                    true,
                    "Pixel 9",
                    7,
                    ThemeMode.DARK,
                    false,
                    0xFF00FF00.toInt(),
                    true,
                    AccessMode.FULL_DRIVE,
                ),
                repo.current(),
            )
            repo.setAccount(null, null)
            repo.setDeviceName(" ")
            assertEquals(null, repo.current().accountEmail)
            assertEquals(null, repo.current().deviceName)
            repo.clear()
            assertEquals(AppSettings(), repo.current())
        }

    @Test
    fun `access mode defaults and switches back`() =
        runTest {
            val repo = repo()
            assertEquals(AccessMode.APP_FOLDER, repo.current().accessMode)
            repo.setAccessMode(AccessMode.FULL_DRIVE)
            assertEquals(AccessMode.FULL_DRIVE, repo.current().accessMode)
            repo.setAccessMode(AccessMode.APP_FOLDER)
            assertEquals(AccessMode.APP_FOLDER, repo.current().accessMode)
        }

    @Test
    fun `unknown access mode falls back`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "u.preferences_pb") }
            store.edit { it[stringPreferencesKey("access_mode")] = "SOMETHING_NEW" }
            assertEquals(AccessMode.APP_FOLDER, SettingsRepository(store).current().accessMode)
        }

    @Test
    fun `limits are enforced`() =
        runTest {
            val repo = repo()
            repo.setSyncInterval(1)
            assertEquals(15, repo.current().syncIntervalMinutes)
            repo.setSyncInterval(100_000)
            assertEquals(1440, repo.current().syncIntervalMinutes)
            repo.setLogRetentionDays(0)
            assertEquals(1, repo.current().logRetentionDays)
        }
}
