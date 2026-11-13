package io.github.drivesync.core.localstorage

import io.github.drivesync.core.localstorage.tree.DocumentInfo
import io.github.drivesync.core.localstorage.tree.DocumentTree
import io.github.drivesync.core.localstorage.tree.TreeUnavailableException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [DocumentTree] on a real directory, behaving like Android's ExternalStorageProvider: document ids are paths
 * relative to the root, so they change on rename; a clashing name gets " (1)"; illegal characters become `_`.
 * Options mimic other providers and storage (case-insensitive names, no move support).
 */
class FileDocumentTree(
    private val rootDir: File,
    var caseInsensitive: Boolean = false,
    var supportsMove: Boolean = true,
) : DocumentTree {
    /** When set, every call fails as if the grant was revoked or the SD card removed. */
    var unavailable = false

    private var clock = 1_000_000L

    override fun root(): DocumentInfo {
        checkAvailable()
        return info(rootDir) ?: throw TreeUnavailableException("root is gone")
    }

    override fun query(documentId: String): DocumentInfo? {
        checkAvailable()
        return info(fileOf(documentId))
    }

    override fun children(directoryId: String): List<DocumentInfo> {
        checkAvailable()
        val dir = fileOf(directoryId)
        return dir
            .listFiles()
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull(::info)
    }

    override fun openInput(documentId: String): InputStream = existing(documentId).inputStream()

    override fun openOutput(documentId: String): OutputStream {
        val file = existing(documentId)
        return object : FileOutputStream(file) {
            override fun close() {
                super.close()
                file.setLastModified(tick())
            }
        }
    }

    override fun createFile(
        directoryId: String,
        name: String,
    ): DocumentInfo {
        val file = uniqueChild(existing(directoryId), name)
        file.createNewFile()
        file.setLastModified(tick())
        return info(file)!!
    }

    override fun createDirectory(
        directoryId: String,
        name: String,
    ): DocumentInfo {
        val dir = uniqueChild(existing(directoryId), name)
        if (!dir.mkdir()) throw IOException("mkdir $dir failed")
        return info(dir)!!
    }

    override fun rename(
        documentId: String,
        name: String,
    ): DocumentInfo {
        val file = existing(documentId)
        val target = uniqueChild(file.parentFile!!, name, except = file)
        if (!file.renameTo(target)) throw IOException("rename failed")
        return info(target)!!
    }

    override fun move(
        documentId: String,
        fromDirectoryId: String,
        toDirectoryId: String,
    ): DocumentInfo {
        if (!supportsMove) throw UnsupportedOperationException("move not supported")
        val file = existing(documentId)
        check(file.parentFile == fileOf(fromDirectoryId)) { "wrong source parent" }
        val target = uniqueChild(existing(toDirectoryId), file.name)
        if (!file.renameTo(target)) throw IOException("move failed")
        return info(target)!!
    }

    override fun delete(documentId: String) {
        if (!existing(documentId).deleteRecursively()) throw IOException("delete failed")
    }

    // ---- Test helpers (the user's own edits) ----

    fun write(
        path: String,
        content: String,
    ) {
        val file = File(rootDir, path)
        file.parentFile!!.mkdirs()
        file.writeText(content)
        file.setLastModified(tick())
    }

    fun read(path: String): String? = File(rootDir, path).takeIf { it.isFile }?.readText()

    fun exists(path: String) = File(rootDir, path).exists()

    fun userMove(
        from: String,
        to: String,
    ) {
        File(rootDir, to).parentFile!!.mkdirs()
        check(File(rootDir, from).renameTo(File(rootDir, to)))
    }

    /** Files below the root (relative path → content), recycle folder and temps included. */
    fun files(): Map<String, String> =
        rootDir
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(rootDir).invariantSeparatorsPath to it.readText() }
            .toSortedMap()

    // ---- Internals ----

    private fun info(file: File): DocumentInfo? {
        if (!file.exists()) return null
        val id = file.relativeTo(rootDir).invariantSeparatorsPath
        return DocumentInfo(
            documentId = id,
            name = if (file == rootDir) "" else file.name,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            lastModified = file.lastModified(),
        )
    }

    private fun fileOf(documentId: String) = if (documentId.isEmpty()) rootDir else File(rootDir, documentId)

    private fun existing(documentId: String): File {
        checkAvailable()
        return fileOf(documentId).takeIf { it.exists() } ?: throw IOException("no document $documentId")
    }

    private fun uniqueChild(
        dir: File,
        wanted: String,
        except: File? = null,
    ): File {
        val clean = wanted.map { if (it in "\"*/:<>?\\|") '_' else it }.joinToString("")
        val stem = clean.substringBeforeLast('.', clean)
        val ext = clean.removePrefix(stem)
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) clean else "$stem ($it)$ext" }
            .map { File(dir, it) }
            .first { candidate ->
                dir.listFiles().orEmpty().none {
                    it != except &&
                        it.name.equals(candidate.name, caseInsensitive)
                }
            }
    }

    private fun checkAvailable() {
        if (unavailable) throw TreeUnavailableException("permission revoked")
    }

    private fun tick(): Long {
        clock += 1000
        return clock
    }
}
