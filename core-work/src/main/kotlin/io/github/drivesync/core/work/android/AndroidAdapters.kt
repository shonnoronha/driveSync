package io.github.drivesync.core.work.android

import android.os.Build
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.drive.store.DriveRemoteStoreFactory
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.sync.store.LocalStore
import io.github.drivesync.core.sync.store.RemoteStore
import io.github.drivesync.core.work.SyncEnvironment
import io.github.drivesync.core.work.SyncStores
import java.time.LocalDateTime
import javax.inject.Inject

/** Production stores: the pair's SAF tree and its Drive folder. */
class AndroidSyncStores
    @Inject
    constructor(
        private val trees: SafTrees,
        private val drive: DriveRemoteStoreFactory,
    ) : SyncStores {
        override fun local(pair: SyncPair): LocalStore = trees.store(pair.localTreeUri)

        override fun remote(pair: SyncPair): RemoteStore = drive.create(pair.driveFolderId)
    }

class AndroidSyncEnvironment
    @Inject
    constructor() : SyncEnvironment {
        override fun now(): LocalDateTime = LocalDateTime.now()

        override val defaultDeviceName: String get() = Build.MODEL ?: "Android"
    }
