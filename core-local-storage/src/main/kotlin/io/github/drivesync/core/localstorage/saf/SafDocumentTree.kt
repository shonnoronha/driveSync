package io.github.drivesync.core.localstorage.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.drivesync.core.localstorage.tree.DocumentInfo
import io.github.drivesync.core.localstorage.tree.DocumentTree
import io.github.drivesync.core.localstorage.tree.TreeUnavailableException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [DocumentTree] over a Storage Access Framework tree. Listings query `DocumentsContract` columns directly,
 * one binder call per directory, instead of `DocumentFile`'s call per property (PRD 3.6).
 */
@Suppress("TooManyFunctions") // One function per DocumentTree operation, plus small helpers.
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentTree {
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun root(): DocumentInfo =
        guarded { query(rootId) } ?: throw TreeUnavailableException("The phone folder no longer exists")

    override fun query(documentId: String): DocumentInfo? =
        try {
            guarded { resolver.query(documentUri(documentId), PROJECTION, null, null, null) }
                ?.use { if (it.moveToFirst()) it.toInfo() else null }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null // Providers reject ids of deleted documents this way.
        }

    override fun children(directoryId: String): List<DocumentInfo> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
        return try {
            guarded { resolver.query(uri, PROJECTION, null, null, null) }?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toInfo()) }
            } ?: throw TreeUnavailableException("Listing the phone folder failed")
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    override fun openInput(documentId: String): InputStream =
        guarded { resolver.openInputStream(documentUri(documentId)) } ?: throw IOException("cannot read $documentId")

    override fun openOutput(documentId: String): OutputStream =
        guarded { resolver.openOutputStream(documentUri(documentId), "wt") }
            ?: throw IOException("cannot write $documentId")

    override fun createFile(
        directoryId: String,
        name: String,
    ): DocumentInfo = create(directoryId, BINARY_MIME_TYPE, name)

    override fun createDirectory(
        directoryId: String,
        name: String,
    ): DocumentInfo = create(directoryId, Document.MIME_TYPE_DIR, name)

    override fun rename(
        documentId: String,
        name: String,
    ): DocumentInfo {
        val uri =
            guarded { DocumentsContract.renameDocument(resolver, documentUri(documentId), name) }
                ?: throw IOException("renaming $documentId failed")
        return requireInfo(DocumentsContract.getDocumentId(uri))
    }

    override fun move(
        documentId: String,
        fromDirectoryId: String,
        toDirectoryId: String,
    ): DocumentInfo {
        val flags = guarded { flagsOf(documentId) }
        if (flags and Document.FLAG_SUPPORTS_MOVE == 0) throw UnsupportedOperationException("move not supported")
        val uri =
            guarded {
                DocumentsContract.moveDocument(
                    resolver,
                    documentUri(documentId),
                    documentUri(fromDirectoryId),
                    documentUri(toDirectoryId),
                )
            } ?: throw IOException("moving $documentId failed")
        return requireInfo(DocumentsContract.getDocumentId(uri))
    }

    override fun delete(documentId: String) {
        if (!guarded { DocumentsContract.deleteDocument(resolver, documentUri(documentId)) }) {
            throw IOException("deleting $documentId failed")
        }
    }

    private fun create(
        directoryId: String,
        mimeType: String,
        name: String,
    ): DocumentInfo {
        val uri =
            guarded { DocumentsContract.createDocument(resolver, documentUri(directoryId), mimeType, name) }
                ?: throw IOException("creating $name failed")
        return requireInfo(DocumentsContract.getDocumentId(uri))
    }

    private fun requireInfo(documentId: String) = query(documentId) ?: throw IOException("$documentId vanished")

    private fun flagsOf(documentId: String): Int =
        resolver.query(documentUri(documentId), arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0

    private fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** A revoked grant shows up as SecurityException: the pair pauses (PRD 6.2). */
    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw TreeUnavailableException("Access to the phone folder was lost", e)
        }

    private fun Cursor.toInfo(): DocumentInfo =
        DocumentInfo(
            documentId = getString(COLUMN_ID),
            name = getString(COLUMN_NAME).orEmpty(),
            isDirectory = getString(COLUMN_MIME) == Document.MIME_TYPE_DIR,
            size = if (isNull(COLUMN_SIZE)) 0 else getLong(COLUMN_SIZE),
            lastModified = if (isNull(COLUMN_MODIFIED)) 0 else getLong(COLUMN_MODIFIED),
        )

    private companion object {
        const val BINARY_MIME_TYPE = "application/octet-stream"

        // Indexes into PROJECTION.
        const val COLUMN_ID = 0
        const val COLUMN_NAME = 1
        const val COLUMN_MIME = 2
        const val COLUMN_SIZE = 3
        const val COLUMN_MODIFIED = 4
        val PROJECTION =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
    }
}
