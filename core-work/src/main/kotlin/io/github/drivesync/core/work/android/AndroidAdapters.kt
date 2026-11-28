package io.github.drivesync.core.work.android

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.drive.auth.AccessModeSource
import io.github.drivesync.core.drive.store.DriveRemoteStoreFactory
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.sync.store.AccessMode
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

        override suspend fun remote(pair: SyncPair): RemoteStore = drive.create(pair.driveFolderId)
    }

/** The saved access mode, for the Drive scopes and the pair stores (PRD 6.4). */
class SettingsAccessMode
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : AccessModeSource {
        override suspend fun current(): AccessMode = settings.current().accessMode
    }

class AndroidSyncEnvironment
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncEnvironment {
        override fun now(): LocalDateTime = LocalDateTime.now()

        override val defaultDeviceName: String get() = Build.MODEL ?: "Android"

        override fun unmeteredNetwork(): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
            return connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered
        }

        override fun charging(): Boolean = context.getSystemService(BatteryManager::class.java)?.isCharging ?: false
    }
