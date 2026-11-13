package io.github.drivesync.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.data.settings.ThemeMode
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
