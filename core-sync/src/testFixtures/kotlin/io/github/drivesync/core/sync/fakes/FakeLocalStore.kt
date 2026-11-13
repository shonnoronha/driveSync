package io.github.drivesync.core.sync.fakes

import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.store.LocalStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class LocalOp { SCAN, STAT, MD5, READ, WRITE_TEMP, COMMIT_TEMP, DELETE_TEMP, CREATE_FOLDER, MOVE, RECYCLE }

/**
 * In-memory local tree. "User" helpers ([write], [delete], [rename], ...) change it like another app would and
 * never trigger hooks. Store operations call [beforeOp] first: throw from it to inject a failure, or change the
 * tree from it to simulate a concurrent edit.
 *
 * Local mtimes come from their own clock, deliberately unrelated to Drive's (PRD 3.9 rule 3).
 */
class FakeLocalStore(
    startTime: Long = 1_000_000L,
) : LocalStore {
    private class Node(
        val isFolder: Boolean,
        var bytes: ByteArray,
        var lastModified: Long,
        val documentId: String,
    )

    private val nodes = sortedMapOf<RelPath, Node>()
    private var now = startTime
    private var nextDoc = 1

    /** When set, [scan] returns [Snapshot.Unavailable] with this reason (SD card removed, permission lost). */
    var unavailableReason: String? = null

    var beforeOp: (suspend (op: LocalOp, path: RelPath?) -> Unit)? = null

    // ---- User-side helpers ----

    fun write(
        path: String,
        content: String,
    ) {
        val p = RelPath.of(path)
        p.parent?.let { mkdirs(it) }
        val existing = nodes[p]
        if (existing != null && !existing.isFolder) {
            existing.bytes = content.toByteArray()
            existing.lastModified = tick()
        } else {
            nodes[p] = Node(false, content.toByteArray(), tick(), newDocId())
        }
    }

    /** Changes the mtime but not the content. */
    fun touch(path: String) {
        node(RelPath.of(path)).lastModified = tick()
    }

    fun mkdir(path: String) = mkdirs(RelPath.of(path))

    fun delete(path: String) {
        val p = RelPath.of(path)
        nodes.keys.filter { it == p || p.isAncestorOf(it) }.forEach { nodes.remove(it) }
    }

    fun rename(
        from: String,
        to: String,
    ) = moveTree(RelPath.of(from), RelPath.of(to))

    fun content(path: String): String? = nodes[RelPath.of(path)]?.takeIf { !it.isFolder }?.bytes?.decodeToString()

    fun exists(path: String): Boolean = RelPath.of(path) in nodes

    /** Synced files (path → content), without the recycle folder or temp files. */
    fun files(): Map<String, String> =
        nodes
            .filter { (p, n) -> !n.isFolder && !IgnoreRules.isIgnored(p) }
            .map { (p, n) -> p.value to n.bytes.decodeToString() }
            .toMap()

    fun folders(): Set<String> =
        nodes
            .filter { (p, n) -> n.isFolder && !IgnoreRules.isIgnored(p) }
            .keys
            .map { it.value }
            .toSet()

    /** Everything in the recycle folder (path → content), paths relative to the recycle folder. */
    fun recycled(): Map<String, String> =
        nodes
            .filter { (p, n) -> !n.isFolder && p.segments.first() == IgnoreRules.RECYCLE_FOLDER }
            .map { (p, n) -> p.value.removePrefix(IgnoreRules.RECYCLE_FOLDER + "/") to n.bytes.decodeToString() }
            .toMap()

    fun tempFiles(): Set<String> =
        nodes.keys
            .filter { it.name.endsWith(IgnoreRules.TEMP_SUFFIX) }
            .map { it.value }
            .toSet()

    // ---- LocalStore ----

    override suspend fun scan(): Snapshot<LocalEntry> {
        hook(LocalOp.SCAN, null)
        unavailableReason?.let { return Snapshot.Unavailable(it) }
        return Snapshot.Available(nodes.map { (p, n) -> entry(p, n) })
    }

    override suspend fun stat(path: RelPath): LocalEntry? {
        hook(LocalOp.STAT, path)
        return nodes[path]?.let { entry(path, it) }
    }

    override suspend fun md5(path: RelPath): String {
        hook(LocalOp.MD5, path)
        return md5Hex(fileNode(path).bytes)
    }

    override suspend fun <T> read(
        path: RelPath,
        block: suspend (InputStream) -> T,
    ): T {
        val bytes = fileNode(path).bytes
        hook(LocalOp.READ, path)
        return block(ByteArrayInputStream(bytes))
    }

    override suspend fun writeTemp(
        temp: RelPath,
        block: suspend (OutputStream) -> Unit,
    ): LocalEntry {
        hook(LocalOp.WRITE_TEMP, temp)
        requireParent(temp)
        // Like SAF, the temp document exists before any byte arrives: a failed transfer leaves a partial file.
        val n = Node(false, ByteArray(0), tick(), newDocId())
        nodes[temp] = n
        val out = ByteArrayOutputStream()
        try {
            block(out)
        } finally {
            n.bytes = out.toByteArray()
            n.lastModified = tick()
        }
        return entry(temp, n)
    }

    override suspend fun commitTemp(
        temp: RelPath,
        target: RelPath,
        recycleExisting: Boolean,
    ): LocalEntry {
        hook(LocalOp.COMMIT_TEMP, target)
        val n = fileNode(temp)
        if (target in nodes && recycleExisting) moveTree(target, recycleTarget(target))
        nodes.remove(temp)
        nodes[target] = n
        return entry(target, n)
    }

    override suspend fun deleteTemp(temp: RelPath) {
        hook(LocalOp.DELETE_TEMP, temp)
        nodes.remove(temp)
    }

    override suspend fun createFolder(path: RelPath): LocalEntry {
        hook(LocalOp.CREATE_FOLDER, path)
        nodes[path]?.let {
            if (!it.isFolder) throw IOException("a file exists at $path")
            return entry(path, it)
        }
        requireParent(path)
        val n = Node(true, ByteArray(0), tick(), newDocId())
        nodes[path] = n
        return entry(path, n)
    }

    override suspend fun move(
        from: RelPath,
        to: RelPath,
    ): LocalEntry {
        hook(LocalOp.MOVE, from)
        if (to in nodes) throw IOException("$to already exists")
        requireParent(to)
        moveTree(from, to)
        return entry(to, node(to))
    }

    override suspend fun recycle(path: RelPath) {
        hook(LocalOp.RECYCLE, path)
        node(path)
        moveTree(path, recycleTarget(path))
    }

    // ---- Internals ----

    private suspend fun hook(
        op: LocalOp,
        path: RelPath?,
    ) {
        beforeOp?.invoke(op, path)
    }

    private fun tick(): Long = ++now

    private fun newDocId(): String = "doc-${nextDoc++}"

    private fun node(path: RelPath): Node = nodes[path] ?: throw IOException("no such local entry: $path")

    private fun fileNode(path: RelPath): Node {
        val n = node(path)
        if (n.isFolder) throw IOException("$path is a folder")
        return n
    }

    private fun requireParent(path: RelPath) {
        val parent = path.parent ?: return
        if (nodes[parent]?.isFolder != true) throw IOException("parent folder missing: $parent")
    }

    private fun mkdirs(path: RelPath) {
        path.parent?.let { mkdirs(it) }
        if (path !in nodes) nodes[path] = Node(true, ByteArray(0), tick(), newDocId())
    }

    private fun moveTree(
        from: RelPath,
        to: RelPath,
    ) {
        val moving = nodes.filterKeys { it == from || from.isAncestorOf(it) }.toList()
        if (moving.isEmpty()) throw IOException("no such local entry: $from")
        to.parent?.let { mkdirs(it) }
        moving.forEach { (p, _) -> nodes.remove(p) }
        moving.forEach { (p, n) -> nodes[RelPath.of(to.value + p.value.removePrefix(from.value))] = n }
    }

    private fun recycleTarget(path: RelPath): RelPath {
        var candidate = RelPath.of("${IgnoreRules.RECYCLE_FOLDER}/${path.value}")
        var n = 2
        while (candidate in nodes) {
            candidate = RelPath.of("${IgnoreRules.RECYCLE_FOLDER}/${path.value} ($n)")
            n++
        }
        return candidate
    }

    private fun entry(
        path: RelPath,
        n: Node,
    ) = LocalEntry(
        path = path,
        isFolder = n.isFolder,
        size = if (n.isFolder) 0 else n.bytes.size.toLong(),
        lastModified = n.lastModified,
        md5 = null,
        documentId = n.documentId,
    )
}
