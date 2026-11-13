package io.github.drivesync.core.work

import java.net.URLDecoder

/**
 * Which pairs live in media folders, where MediaStore content-URI triggers start a sync about a minute after a
 * new photo or video (PRD 3.4, 3.6). Works on ExternalStorageProvider tree URIs, the only kind v1 allows.
 */
object MediaFolders {
    private val MEDIA_ROOTS = setOf("dcim", "pictures", "movies")

    fun isMediaTree(treeUri: String): Boolean {
        val encodedId = treeUri.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/')
        if (encodedId.isEmpty()) return false
        val documentId = URLDecoder.decode(encodedId, Charsets.UTF_8.name())
        val path = documentId.substringAfter(':', missingDelimiterValue = "")
        return path.substringBefore('/').lowercase() in MEDIA_ROOTS
    }
}
