package io.github.drivesync.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.AppSettings
import io.github.drivesync.core.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class AppDataCleanerTest : DbTest() {
    @Test
    fun `sign-out forgets pairs and settings`() =
        runTest {
            val file = Files.createTempDirectory("s").resolve("s.preferences_pb").toFile()
            val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
            val pairs = PairRepository(db)
            settings.setAccount("me@example.com", "root")
            pairs.add("p", "Notes", "t", "f")
            AppDataCleaner(db, settings).clearAll()
            assertEquals(emptyList<Any>(), pairs.pairs())
            assertEquals(AppSettings(), settings.current())
        }
}
