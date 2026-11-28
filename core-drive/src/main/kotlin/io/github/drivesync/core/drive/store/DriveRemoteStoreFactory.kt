package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.auth.AccessModeSource
import io.github.drivesync.core.sync.store.RemoteStore
import javax.inject.Inject

/** Builds the [RemoteStore] of one pair from its Drive folder id, in the saved access mode. */
class DriveRemoteStoreFactory
    @Inject
    constructor(
        private val client: DriveClient,
        private val accessMode: AccessModeSource,
    ) {
        suspend fun create(pairFolderId: String): RemoteStore =
            DriveRemoteStore(client, pairFolderId, accessMode.current())
    }
