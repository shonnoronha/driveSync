package io.github.drivesync.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.drivesync.core.data.db.DriveSyncDatabase
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Base for Room tests: a fresh in-memory database per test (Robolectric, JUnit 4). */
@RunWith(RobolectricTestRunner::class)
abstract class DbTest {
    protected val db: DriveSyncDatabase =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                DriveSyncDatabase::class.java,
            ).allowMainThreadQueries()
            .build()

    protected var now = 1_000L

    @After
    fun closeDb() = db.close()
}
