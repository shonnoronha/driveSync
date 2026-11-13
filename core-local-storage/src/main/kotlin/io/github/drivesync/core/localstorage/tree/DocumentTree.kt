package io.github.drivesync.core.localstorage.tree

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One file or directory as a documents provider reports it. */
data class DocumentInfo(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
)

/**
 * The blocking document operations of one granted tree (a Storage Access Framework tree in production).
 * Kept this small so all sync logic sits in [DocumentTreeLocalStore] and is testable on the JVM.
 *
 * Providers may change a requested name (case-insensitive clash, illegal characters, " (1)" suffixes), so callers
 * must check the returned [DocumentInfo.name].
 */
interface DocumentTree {
    /** The tree's root directory. Throws [TreeUnavailableException] when it cannot be read. */
    fun root(): DocumentInfo

    /** Current state of a document, or null when it no longer exists. */
    fun query(documentId: String): DocumentInfo?

    /**
     * Direct children. Empty when the directory has vanished since it was listed; throws
     * [TreeUnavailableException] when the tree itself can no longer be read.
     */
    fun children(directoryId: String): List<DocumentInfo>

    fun openInput(documentId: String): InputStream

    /** Opens for writing, truncating existing content. */
    fun openOutput(documentId: String): OutputStream

    fun createFile(
        directoryId: String,
        name: String,
    ): DocumentInfo

    fun createDirectory(
        directoryId: String,
        name: String,
    ): DocumentInfo

    /** Renames in place. The document id may change. */
    fun rename(
        documentId: String,
        name: String,
    ): DocumentInfo

    /** Moves into another directory, keeping the name. Throws [UnsupportedOperationException] if unsupported. */
    fun move(
        documentId: String,
        fromDirectoryId: String,
        toDirectoryId: String,
    ): DocumentInfo

    /** Permanent delete. Only used for the app's own temp files, replaced synced copies and completed moves. */
    fun delete(documentId: String)
}

/** The tree is gone or the permission was revoked: the pair must pause, never read it as empty (PRD 3.9 rule 1). */
class TreeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
