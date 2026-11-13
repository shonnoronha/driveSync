package io.github.drivesync.core.localstorage.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drivesync.core.localstorage.DocumentTreeLocalStore
import io.github.drivesync.core.sync.store.LocalStore
import javax.inject.Inject
import javax.inject.Singleton

/** Folder grants from `ACTION_OPEN_DOCUMENT_TREE` and the [LocalStore] of each pair (PRD 6.2). */
@Singleton
class SafTrees
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val resolver get() = context.contentResolver

        fun store(treeUri: String): LocalStore = DocumentTreeLocalStore(SafDocumentTree(resolver, Uri.parse(treeUri)))

        /** Keeps read + write access across reboots. Call right after the user picks the folder. */
        fun persist(treeUri: String) = resolver.takePersistableUriPermission(Uri.parse(treeUri), READ_WRITE)

        /** Gives the grant back when a pair is removed, freeing one of the limited slots. */
        fun release(treeUri: String) = resolver.releasePersistableUriPermission(Uri.parse(treeUri), READ_WRITE)

        fun isPersisted(treeUri: String): Boolean =
            resolver.persistedUriPermissions.any {
                it.uri.toString() == treeUri && it.isReadPermission &&
                    it.isWritePermission
            }

        /** Android caps persisted grants (128 or 512); the UI warns near the limit. */
        fun persistedCount(): Int = resolver.persistedUriPermissions.size

        /** v1 allows only on-device shared storage; other providers get a warning (PRD 6.2). */
        fun isSupportedProvider(treeUri: String): Boolean = Uri.parse(treeUri).authority == EXTERNAL_STORAGE_AUTHORITY

        private companion object {
            const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
            const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
    }
