package io.github.drivesync.core.localstorage

import java.net.URLDecoder

/** Human-readable names for SAF tree URIs, e.g. `…/tree/primary%3ADCIM%2FCamera` → "DCIM/Camera". */
object TreeUris {
    private const val PRIMARY_VOLUME = "primary"

    data class Location(
        /** Folder path inside the volume; empty for the volume root. */
        val path: String,
        /** False for SD cards and USB drives. */
        val onInternalStorage: Boolean,
    )

    fun location(treeUri: String): Location? {
        val encoded = treeUri.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/')
        if (encoded.isEmpty()) return null
        val documentId = URLDecoder.decode(encoded, Charsets.UTF_8.name())
        if (':' !in documentId) return null
        return Location(documentId.substringAfter(':'), documentId.substringBefore(':') == PRIMARY_VOLUME)
    }

    /** The last folder name, for a new pair's default name. */
    fun folderName(treeUri: String): String? =
        location(treeUri)
            ?.path
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
}
