package io.github.drivesync.core.localstorage

import io.github.drivesync.core.localstorage.tree.DocumentInfo
import io.github.drivesync.core.localstorage.tree.DocumentTree
import io.github.drivesync.core.localstorage.tree.TreeUnavailableException
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.plan.IgnoreRules
import io.github.drivesync.core.sync.store.LocalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * [LocalStore] over a [DocumentTree] (PRD 3.6, 6.2). Paths are resolved through document ids remembered from the
 * last scan or write, and every cached id is re-checked against its current name before use. A miss falls back
 * to listing the parent directory.
 */
@Suppress("TooManyFunctions")
class DocumentTreeLocalStore(
    private val tree: DocumentTree,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalStore {
    private val ids = HashMap<RelPath, String>()

    override suspend fun scan(): Snapshot<LocalEntry> =
        withContext(io) {
            try {
                val root = tree.root()
                if (!root.isDirectory) return@withContext Snapshot.Unavailable("The phone folder is not a folder")
                ids.clear()
                val entries = mutableListOf<LocalEntry>()
                val pending = ArrayDeque(listOf<Pair<String, RelPath?>>(root.documentId to null))
                while (pending.isNotEmpty()) {
                    val (directoryId, directoryPath) = pending.removeFirst()
                    for (child in tree.children(directoryId)) {
                        if (child.name.isEmpty() || '/' in child.name) continue // Not a real file name.
                        val path = directoryPath?.child(child.name) ?: RelPath.of(child.name)
                        ids[path] = child.documentId
                        entries += child.toEntry(path)
                        if (child.isDirectory) pending += child.documentId to path
                    }
                }
                Snapshot.Available(entries)
            } catch (e: TreeUnavailableException) {
                Snapshot.Unavailable(e.message ?: "The phone folder cannot be read")
            }
        }

    override suspend fun stat(path: RelPath): LocalEntry? = withContext(io) { resolve(path)?.toEntry(path) }

    override suspend fun md5(path: RelPath): String =
        withContext(io) {
            val digest = MessageDigest.getInstance("MD5")
            tree.openInput(file(path).documentId).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    override suspend fun <T> read(
        path: RelPath,
        block: suspend (InputStream) -> T,
    ): T {
        val input = withContext(io) { tree.openInput(file(path).documentId) }
        return input.use { block(it) }
    }

    override suspend fun writeTemp(
        temp: RelPath,
        block: suspend (OutputStream) -> Unit,
    ): LocalEntry {
        requireTemp(temp)
        checkName(temp.name)
        val created =
            withContext(io) {
                resolve(temp)?.let { tree.delete(it.documentId) } // A leftover from a killed run.
                val parent = directory(temp.parent)
                tree.createFile(parent.documentId, temp.name).also {
                    verifyName(it, temp)
                    remember(temp, it)
                }
            }
        withContext(io) { tree.openOutput(created.documentId) }.use { block(it) }
        return withContext(io) { file(temp).toEntry(temp) }
    }

    override suspend fun commitTemp(
        temp: RelPath,
        target: RelPath,
        recycleExisting: Boolean,
    ): LocalEntry =
        withContext(io) {
            requireTemp(temp)
            check(temp.parent == target.parent) { "temp $temp is not next to $target" }
            checkName(target.name)
            val tempDoc = file(temp)
            resolve(target)?.let { existing ->
                if (recycleExisting) recycleNow(target) else tree.delete(existing.documentId)
            }
            val renamed = tree.rename(tempDoc.documentId, target.name)
            forget(temp)
            verifyName(renamed, target)
            remember(target, renamed)
            renamed.toEntry(target)
        }

    override suspend fun deleteTemp(temp: RelPath) =
        withContext(io) {
            requireTemp(temp)
            resolve(temp)?.let { tree.delete(it.documentId) }
            forget(temp)
        }

    override suspend fun createFolder(path: RelPath): LocalEntry =
        withContext(io) {
            val existing = resolve(path)
            when {
                existing == null -> ensureDirectory(path).toEntry(path)
                existing.isDirectory -> existing.toEntry(path)
                else -> throw IOException("$path is a file, not a folder")
            }
        }

    override suspend fun move(
        from: RelPath,
        to: RelPath,
    ): LocalEntry = withContext(io) { moveNow(from, to).toEntry(to) }

    override suspend fun recycle(path: RelPath) = withContext(io) { recycleNow(path) }

    // ---- Internals (all called on [io]) ----

    private fun recycleNow(path: RelPath) {
        resolve(path) ?: throw IOException("$path does not exist")
        val base = "${IgnoreRules.RECYCLE_FOLDER}/${path.value}"
        val target =
            generateSequence(1) { it + 1 }
                .map { RelPath.of(if (it == 1) base else "$base ($it)") }
                .first { resolve(it) == null }
        target.parent?.let(::ensureDirectory)
        moveNow(path, target)
    }

    /** Rename and/or re-parent. The rename happens on whichever side has the name free. */
    private fun moveNow(
        from: RelPath,
        to: RelPath,
    ): DocumentInfo {
        checkName(to.name)
        val source = resolve(from) ?: ioError("$from does not exist")
        if (resolve(to) != null) ioError("$to already exists")
        val sourceParent = directory(from.parent)
        val targetParent = directory(to.parent)
        val result =
            when {
                sourceParent.documentId == targetParent.documentId -> {
                    tree.rename(source.documentId, to.name)
                }

                from.name == to.name -> {
                    relocate(source, sourceParent, targetParent)
                }

                resolve(from.sibling(to.name)) == null -> {
                    val renamed = tree.rename(source.documentId, to.name)
                    relocate(renamed, sourceParent, targetParent)
                }

                resolve(to.sibling(from.name)) == null -> {
                    val moved = relocate(source, sourceParent, targetParent)
                    tree.rename(moved.documentId, to.name)
                }

                else -> {
                    ioError("cannot move $from to $to: both names are taken")
                }
            }
        forgetTree(from)
        verifyName(result, to)
        remember(to, result)
        return result
    }

    /** Moves to another directory; providers without move support get a verified copy, then the source deleted. */
    private fun relocate(
        source: DocumentInfo,
        from: DocumentInfo,
        to: DocumentInfo,
    ): DocumentInfo =
        try {
            tree.move(source.documentId, from.documentId, to.documentId)
        } catch (_: UnsupportedOperationException) {
            val copy = copyTree(source, to)
            tree.delete(source.documentId)
            copy
        }

    private fun copyTree(
        source: DocumentInfo,
        into: DocumentInfo,
    ): DocumentInfo {
        if (source.isDirectory) {
            val directory = tree.createDirectory(into.documentId, source.name)
            tree.children(source.documentId).forEach { copyTree(it, directory) }
            return directory
        }
        val copy = tree.createFile(into.documentId, source.name)
        tree.openInput(source.documentId).use { input -> tree.openOutput(copy.documentId).use { input.copyTo(it) } }
        val written = tree.query(copy.documentId)
        if (written == null || written.size != source.size) throw IOException("copy of ${source.name} is incomplete")
        return written
    }

    private fun ensureDirectory(path: RelPath): DocumentInfo {
        resolve(path)?.let { return if (it.isDirectory) it else throw IOException("$path is a file, not a folder") }
        checkName(path.name)
        val parent = path.parent?.let(::ensureDirectory) ?: tree.root()
        val created = tree.createDirectory(parent.documentId, path.name)
        verifyName(created, path)
        remember(path, created)
        return created
    }

    /** The document at [path], or null. */
    private fun resolve(path: RelPath): DocumentInfo? {
        ids[path]?.let { id ->
            val cached = tree.query(id)
            if (cached != null && cached.name == path.name) return cached
            forget(path)
        }
        val parent = (if (path.parent == null) tree.root() else resolve(path.parent!!)) ?: return null
        if (!parent.isDirectory) return null
        val found = tree.children(parent.documentId).firstOrNull { it.name == path.name } ?: return null
        remember(path, found)
        return found
    }

    private fun directory(path: RelPath?): DocumentInfo {
        if (path == null) return tree.root()
        val found = resolve(path) ?: throw IOException("folder $path does not exist")
        if (!found.isDirectory) throw IOException("$path is a file, not a folder")
        return found
    }

    private fun file(path: RelPath): DocumentInfo {
        val found = resolve(path) ?: throw IOException("$path does not exist")
        if (found.isDirectory) throw IOException("$path is a folder, not a file")
        return found
    }

    private fun verifyName(
        document: DocumentInfo,
        path: RelPath,
    ) {
        if (document.name != path.name) {
            throw IOException("the phone stored ${path.name} as ${document.name} (name not allowed or taken)")
        }
    }

    private fun ioError(message: String): Nothing = throw IOException(message)

    private fun checkName(name: String) {
        LocalNames.problem(name)?.let { throw IOException("cannot write \"$name\": $it") }
    }

    private fun requireTemp(temp: RelPath) =
        require(temp.name.endsWith(IgnoreRules.TEMP_SUFFIX)) { "$temp is not a temp file" }

    private fun remember(
        path: RelPath,
        document: DocumentInfo,
    ) {
        ids[path] = document.documentId
    }

    private fun forget(path: RelPath) {
        ids.remove(path)
    }

    /** Drops [path] and everything cached below it (ids of moved children may now point elsewhere). */
    private fun forgetTree(path: RelPath) {
        ids.keys.removeAll { it == path || path.isAncestorOf(it) }
    }

    private fun DocumentInfo.toEntry(path: RelPath) =
        LocalEntry(
            path = path,
            isFolder = isDirectory,
            size = if (isDirectory) 0 else size,
            lastModified = if (isDirectory) 0 else lastModified,
            documentId = documentId,
        )

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
