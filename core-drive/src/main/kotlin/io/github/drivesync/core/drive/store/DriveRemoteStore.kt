package io.github.drivesync.core.drive.store

import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.api.DriveFile
import io.github.drivesync.core.drive.api.DriveQuery
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.store.AccessMode
import io.github.drivesync.core.sync.store.RemoteStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [RemoteStore] for one pair, rooted at the pair's Drive folder id (PRD 3.8). Paths are rebuilt from parent ids on
 * every call instead of being cached, so the re-checks before writes (PRD 3.9 rule 4) see Drive as it is now.
 * Where Drive holds several folders with one name, the one with the lowest id is used to place new items.
 */
class DriveRemoteStore(
    private val client: DriveClient,
    private val pairFolderId: String,
) : RemoteStore {
    override val accessMode: AccessMode = AccessMode.APP_FOLDER

    override suspend fun scan(): Snapshot<RemoteEntry> {
        val pairFolder = client.getOrNull(pairFolderId)
        when {
            pairFolder == null -> return Snapshot.Unavailable("The pair's Drive folder no longer exists")
            pairFolder.trashed -> return Snapshot.Unavailable("The pair's Drive folder is in the trash")
            !pairFolder.isFolder -> return Snapshot.Unavailable("The pair's Drive item is not a folder")
        }
        val entries = mutableListOf<RemoteEntry>()
        val visited = mutableSetOf(pairFolderId)
        // Breadth-first, one query per batch of folders of the same depth.
        var level: Map<String, RelPath?> = mapOf(pairFolderId to null)
        while (level.isNotEmpty()) {
            val found = level.keys.chunked(PARENTS_PER_QUERY).flatMap { scanBatch(it, level) }
            found.mapTo(entries) { (file, path) -> file.toEntry(path) }
            level =
                found.filter { (file, _) -> file.isFolder && visited.add(file.id) }.associate { (f, p) -> f.id to p }
        }
        return Snapshot.Available(entries)
    }

    /** Children of a batch of folders, each with its path. */
    private suspend fun scanBatch(
        batch: List<String>,
        folderPaths: Map<String, RelPath?>,
    ): List<Pair<DriveFile, RelPath>> =
        client.listAll(DriveQuery.childrenOf(batch)).mapNotNull { file ->
            val parent = file.parents.firstOrNull { it in batch } ?: return@mapNotNull null
            file to childPath(folderPaths[parent], file.name)
        }

    override suspend fun stat(fileId: String): RemoteEntry? {
        val file = client.getOrNull(fileId)?.takeUnless { it.trashed } ?: return null
        val path = pathOf(file) ?: return null
        return file.toEntry(path)
    }

    override suspend fun lookup(path: RelPath): List<RemoteEntry> {
        val parents = foldersAt(path.parent).takeIf { it.isNotEmpty() } ?: return emptyList()
        return childrenNamed(parents.map { it.id }, path.name).map { it.toEntry(path) }
    }

    override suspend fun createFolder(path: RelPath): RemoteEntry {
        val parentId = folderIdFor(path.parent)
        return client.createFolder(path.name, parentId).toEntry(path)
    }

    override suspend fun upload(
        path: RelPath,
        replacesFileId: String?,
        modifiedTime: Long,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): RemoteEntry? {
        val uploaded =
            if (replacesFileId != null) {
                client.uploadReplace(replacesFileId, modifiedTime, content, verifyBeforeCommit)
            } else {
                client.uploadNew(folderIdFor(path.parent), path.name, modifiedTime, content, verifyBeforeCommit)
            }
        return uploaded?.toEntry(path)
    }

    override suspend fun download(
        fileId: String,
        sink: OutputStream,
    ) = client.download(fileId, sink)

    override suspend fun move(
        fileId: String,
        to: RelPath,
    ): RemoteEntry {
        val current = client.getOrNull(fileId) ?: throw IOException("Drive file $fileId no longer exists")
        return client.move(fileId, to.name, folderIdFor(to.parent), current.parents).toEntry(to)
    }

    override suspend fun trash(fileId: String) {
        client.trash(fileId)
    }

    /** The path of [file] inside this pair, or null when it is not (or no longer) inside the pair folder. */
    private suspend fun pathOf(file: DriveFile): RelPath? {
        val segments = mutableListOf(DriveNames.toSegment(file.name))
        var parentId = file.parents.firstOrNull()
        repeat(MAX_DEPTH) {
            when (parentId) {
                null -> return null
                pairFolderId -> return RelPath.of(segments.asReversed().joinToString("/"))
            }
            val parent = client.getOrNull(parentId)?.takeUnless { it.trashed } ?: return null
            segments += DriveNames.toSegment(parent.name)
            parentId = parent.parents.firstOrNull()
        }
        return null
    }

    /** All live folders at [path] (the pair folder itself for null), following every same-name duplicate. */
    private suspend fun foldersAt(path: RelPath?): List<DriveFile> {
        var folders = listOf(client.getOrNull(pairFolderId) ?: return emptyList())
        for (segment in path?.segments.orEmpty()) {
            folders = childrenNamed(folders.map { it.id }, segment).filter { it.isFolder }
            if (folders.isEmpty()) return emptyList()
        }
        return folders
    }

    private suspend fun folderIdFor(path: RelPath?): String =
        if (path == null) {
            pairFolderId
        } else {
            foldersAt(path).minByOrNull { it.id }?.id ?: throw IOException("Drive folder $path does not exist")
        }

    private suspend fun childrenNamed(
        parentIds: List<String>,
        segment: String,
    ): List<DriveFile> {
        val exact = client.listAll(DriveQuery.childNamed(parentIds, segment))
        if (!DriveNames.mayBeMapped(segment)) return exact
        val mapped =
            client
                .listAll(
                    DriveQuery.childrenOf(parentIds),
                ).filter { DriveNames.toSegment(it.name) == segment }
        return (exact + mapped).distinctBy { it.id }
    }

    private fun childPath(
        parent: RelPath?,
        name: String,
    ): RelPath {
        val segment = DriveNames.toSegment(name)
        return parent?.child(segment) ?: RelPath.of(segment)
    }

    private companion object {
        /** Keeps `q` well under Drive's query length limit. */
        const val PARENTS_PER_QUERY = 40
        const val MAX_DEPTH = 256
    }
}

internal fun DriveFile.toEntry(path: RelPath): RemoteEntry =
    RemoteEntry(
        path = path,
        fileId = id,
        isFolder = isFolder,
        size = size?.toLongOrNull() ?: 0,
        md5 = md5Checksum,
        version = version?.toLongOrNull() ?: 0,
        modifiedTime = DriveClient.epochMillis(modifiedTime),
        mimeType = mimeType,
    )
