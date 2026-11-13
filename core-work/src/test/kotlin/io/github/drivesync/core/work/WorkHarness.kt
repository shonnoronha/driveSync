package io.github.drivesync.core.work

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.drivesync.core.data.activity.ActivityRepository
import io.github.drivesync.core.data.activity.SyncRecorder
import io.github.drivesync.core.data.db.DriveSyncDatabase
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.fakes.FakeLocalStore
import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/** A Drive that can fail its scan with a chosen exception (auth revoked, Drive full, network down). */
class FailingRemote(
    private val delegate: FakeRemoteStore,
) : RemoteStore by delegate {
    var error: Exception? = null

    override suspend fun scan(): Snapshot<RemoteEntry> = error?.let { throw it } ?: delegate.scan()
}

class RecordingNotifier : SyncNotifier {
    val events = mutableListOf<String>()

    override fun needsAttention(
        pair: SyncPair,
        detail: String,
    ) {
        events += "attention:${pair.name}"
    }

    override fun conflicts(count: Int) {
        events += "conflicts:$count"
    }

    override fun failures(
        pair: SyncPair,
        count: Int,
    ) {
        events += "failures:${pair.name}:$count"
    }
}

/** Everything the coordinator needs, over an in-memory Room database and fake stores. */
class WorkHarness(
    scope: CoroutineScope,
) {
    val db: DriveSyncDatabase =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                DriveSyncDatabase::class.java,
            ).allowMainThreadQueries()
            .build()
    var now: LocalDateTime = LocalDateTime.of(2026, 11, 13, 9, 30)
    private var ids = 0
    private val dir: File = Files.createTempDirectory("settings").toFile()
    val pairs = PairRepository(db, { 0L }, { "pair-${++ids}" })
    val settings =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
    val locals = mutableMapOf<String, FakeLocalStore>()
    val remotes = mutableMapOf<String, FailingRemote>()
    val drives = mutableMapOf<String, FakeRemoteStore>()
    val notifier = RecordingNotifier()

    val environment =
        object : SyncEnvironment {
            override fun now(): LocalDateTime = this@WorkHarness.now

            override val defaultDeviceName = "Pixel"
        }

    private val stores =
        object : SyncStores {
            override fun local(pair: SyncPair): LocalStore = locals.getValue(pair.id)

            override fun remote(pair: SyncPair): RemoteStore = remotes.getValue(pair.id)
        }

    val runner = PairSyncRunner(pairs, SyncRecorder(db) { 0L }, stores, environment)
    val coordinator = SyncCoordinator(pairs, settings, runner, notifier, ActivityRepository(db), environment)

    suspend fun signIn() = settings.setAccount("me@example.com", "root")

    suspend fun addPair(
        name: String,
        treeUri: String = "content://com.android.externalstorage.documents/tree/primary%3A$name",
    ): String {
        val pair = pairs.add(pairs.newPairId(), name, treeUri, "folder-$name")
        locals[pair.id] = FakeLocalStore()
        drives[pair.id] = FakeRemoteStore()
        remotes[pair.id] = FailingRemote(drives.getValue(pair.id))
        return pair.id
    }

    fun close() {
        db.close()
        dir.deleteRecursively()
    }
}
