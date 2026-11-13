package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.sync.store.RemoteStore
import javax.inject.Inject

/** Builds the [RemoteStore] of one pair from its Drive folder id. */
class DriveRemoteStoreFactory
    @Inject
    constructor(
        private val client: DriveClient,
    ) {
        fun create(pairFolderId: String): RemoteStore = DriveRemoteStore(client, pairFolderId)
    }
