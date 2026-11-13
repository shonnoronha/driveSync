package io.github.drivesync.core.sync.fakes

import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.store.AccessMode
import io.github.drivesync.core.sync.store.RemoteStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class RemoteOp { SCAN, STAT, LOOKUP, CREATE_FOLDER, UPLOAD, DOWNLOAD, MOVE, TRASH }

/**
 * In-memory Drive pair folder, keyed by file id like real Drive (several files may share one path).
 * "User" helpers simulate the Drive website or another phone and never trigger hooks; store operations call
 * [beforeOp] first. Drive's clock starts far from the local clock on purpose (PRD 3.9 rule 3).
 */
class FakeRemoteStore(
    override val accessMode: AccessMode = AccessMode.APP_FOLDER,
    startTime: Long = 9_000_000_000_000L,
) : RemoteStore {
    private class Node(
        val fileId: String,
        var path: RelPath,
        val isFolder: Boolean,
        var bytes: ByteArray,
        var version: Long,
        var modifiedTime: Long,
        val mimeType: String,
        var trashed: Boolean = false,
    )

    private val nodes = LinkedHashMap<String, Node>()
    private var now = startTime
    private var nextId = 1

    /** When set, [scan] returns [Snapshot.Unavailable] (pair folder trashed, access revoked). */
    var unavailableReason: String? = null

    var beforeOp: (suspend (op: RemoteOp, path: RelPath?) -> Unit)? = null

    /** Applied to the bytes [download] sends, e.g. to simulate a truncated or corrupted transfer. */
    var downloadTransform: (ByteArray) -> ByteArray = { it }

    // ---- User-side helpers ----

    /** Edits the single live file at [path], or creates it (and missing parent folders). Returns its id. */
    fun write(
        path: String,
        content: String,
    ): String {
        val p = RelPath.of(path)
        val existing = live(p).filter { !it.isFolder }
        if (existing.size > 1) error("several Drive files at $path; use writeDuplicate")
        val n = existing.singleOrNull()
        return if (n != null) {
            n.bytes = content.toByteArray()
            n.version++
            n.modifiedTime = tick()
            n.fileId
        } else {
            create(p, isFolder = false, bytes = content.toByteArray(), mime = RemoteEntry.DEFAULT_MIME_TYPE).fileId
        }
    }

    /** Always creates a new file, even if one with this name exists (Drive allows that). */
    fun writeDuplicate(
        path: String,
        content: String,
    ): String = create(RelPath.of(path), false, content.toByteArray(), RemoteEntry.DEFAULT_MIME_TYPE).fileId

    fun googleDoc(path: String): String = create(RelPath.of(path), false, ByteArray(0), GOOGLE_DOC_MIME_TYPE).fileId

    fun mkdir(path: String): String = mkdirs(RelPath.of(path)).fileId

    fun trashAt(path: String) {
        live(RelPath.of(path)).forEach { trashTree(it) }
    }

    fun rename(
        from: String,
        to: String,
    ) {
        val n = live(RelPath.of(from)).single()
        moveTree(n, RelPath.of(to))
    }

    fun content(path: String): String? = live(RelPath.of(path)).singleOrNull { !it.isFolder }?.bytes?.decodeToString()

    fun fileId(path: String): String = live(RelPath.of(path)).single().fileId

    /** Live (non-trashed) byte files: path → contents (a list, because Drive allows duplicates). */
    fun files(): Map<String, List<String>> =
        nodes.values
            .filter { !it.trashed && !it.isFolder && !it.mimeType.startsWith(RemoteEntry.GOOGLE_APPS_MIME_PREFIX) }
            .groupBy({ it.path.value }, { it.bytes.decodeToString() })

    fun folders(): Set<String> =
        nodes.values
            .filter { !it.trashed && it.isFolder }
            .map { it.path.value }
            .toSet()

    /** Paths of trashed entries (files and folders). */
    fun trashed(): List<String> = nodes.values.filter { it.trashed }.map { it.path.value }

    // ---- RemoteStore ----

    override suspend fun scan(): Snapshot<RemoteEntry> {
        hook(RemoteOp.SCAN, null)
        unavailableReason?.let { return Snapshot.Unavailable(it) }
        return Snapshot.Available(nodes.values.filter { !it.trashed }.map(::entry))
    }

    override suspend fun stat(fileId: String): RemoteEntry? {
        hook(RemoteOp.STAT, nodes[fileId]?.path)
        return nodes[fileId]?.takeIf { !it.trashed }?.let(::entry)
    }

    override suspend fun lookup(path: RelPath): List<RemoteEntry> {
        hook(RemoteOp.LOOKUP, path)
        return live(path).map(::entry)
    }

    override suspend fun createFolder(path: RelPath): RemoteEntry {
        hook(RemoteOp.CREATE_FOLDER, path)
        requireParent(path)
        return entry(create(path, isFolder = true, bytes = ByteArray(0), mime = RemoteEntry.FOLDER_MIME_TYPE))
    }

    override suspend fun upload(
        path: RelPath,
        replacesFileId: String?,
        modifiedTime: Long,
        content: InputStream,
        verifyBeforeCommit: suspend () -> Boolean,
    ): RemoteEntry? {
        hook(RemoteOp.UPLOAD, path)
        requireParent(path)
        val bytes = content.readBytes()
        if (!verifyBeforeCommit()) return null
        val n =
            if (replacesFileId != null) {
                val target = liveById(replacesFileId)
                target.bytes = bytes
                target.version++
                target
            } else {
                create(path, isFolder = false, bytes = bytes, mime = RemoteEntry.DEFAULT_MIME_TYPE)
            }
        n.modifiedTime = modifiedTime
        return entry(n)
    }

    override suspend fun download(
        fileId: String,
        sink: OutputStream,
    ) {
        val n = liveById(fileId)
        hook(RemoteOp.DOWNLOAD, n.path)
        sink.write(downloadTransform(n.bytes))
    }

    override suspend fun move(
        fileId: String,
        to: RelPath,
    ): RemoteEntry {
        val n = liveById(fileId)
        hook(RemoteOp.MOVE, n.path)
        requireParent(to)
        moveTree(n, to)
        return entry(n)
    }

    override suspend fun trash(fileId: String) {
        val n = liveById(fileId)
        hook(RemoteOp.TRASH, n.path)
        trashTree(n)
    }

    // ---- Internals ----

    private suspend fun hook(
        op: RemoteOp,
        path: RelPath?,
    ) {
        beforeOp?.invoke(op, path)
    }

    private fun tick(): Long = ++now

    private fun live(path: RelPath): List<Node> = nodes.values.filter { !it.trashed && it.path == path }

    private fun liveById(fileId: String): Node =
        nodes[fileId]?.takeIf { !it.trashed } ?: throw IOException("no Drive file $fileId")

    private fun requireParent(path: RelPath) {
        val parent = path.parent ?: return
        if (live(parent).none { it.isFolder }) throw IOException("Drive parent folder missing: $parent")
    }

    private fun create(
        path: RelPath,
        isFolder: Boolean,
        bytes: ByteArray,
        mime: String,
    ): Node {
        path.parent?.let { mkdirs(it) }
        val n = Node("f${nextId++}", path, isFolder, bytes, version = 1, modifiedTime = tick(), mimeType = mime)
        nodes[n.fileId] = n
        return n
    }

    private fun mkdirs(path: RelPath): Node =
        live(path).firstOrNull { it.isFolder }
            ?: create(path, isFolder = true, bytes = ByteArray(0), mime = RemoteEntry.FOLDER_MIME_TYPE)

    private fun descendants(n: Node): List<Node> =
        if (n.isFolder) nodes.values.filter { !it.trashed && n.path.isAncestorOf(it.path) } else emptyList()

    private fun moveTree(
        n: Node,
        to: RelPath,
    ) {
        val from = n.path
        descendants(n).forEach { it.path = RelPath.of(to.value + it.path.value.removePrefix(from.value)) }
        n.path = to
        n.modifiedTime = tick()
    }

    private fun trashTree(n: Node) {
        descendants(n).forEach { it.trashed = true }
        n.trashed = true
    }

    private companion object {
        const val GOOGLE_DOC_MIME_TYPE = "application/vnd.google-apps.document"
    }

    private fun entry(n: Node): RemoteEntry {
        val native = n.mimeType.startsWith(RemoteEntry.GOOGLE_APPS_MIME_PREFIX)
        return RemoteEntry(
            path = n.path,
            fileId = n.fileId,
            isFolder = n.isFolder,
            size = if (n.isFolder || native) 0 else n.bytes.size.toLong(),
            md5 = if (n.isFolder || native) null else md5Hex(n.bytes),
            version = n.version,
            modifiedTime = n.modifiedTime,
            mimeType = n.mimeType,
        )
    }
}
