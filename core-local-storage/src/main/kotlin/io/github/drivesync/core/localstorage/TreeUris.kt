package io.github.drivesync.core.localstorage

import java.net.URLDecoder

/** Human-readable names for SAF tree URIs, e.g. `…/tree/primary%3ADCIM%2FCamera` → "DCIM/Camera". */
object TreeUris {
    private const val PRIMARY_VOLUME = "primary"

    data class Location(
        /** Folder path inside the volume; empty for the volume root. */
        val path: String,
        /** "primary" for internal storage, the volume id (e.g. "1234-ABCD") for SD cards and USB drives. */
        val volume: String,
    ) {
        val onInternalStorage: Boolean get() = volume == PRIMARY_VOLUME

        /** True when one folder contains the other (or they are the same): syncing both would loop (FR-P7). */
        fun overlaps(other: Location): Boolean {
            if (volume != other.volume) return false
            val a = path.trim('/')
            val b = other.path.trim('/')
            return a == b || a.isEmpty() || b.isEmpty() || a.startsWith("$b/") || b.startsWith("$a/")
        }
    }

    fun location(treeUri: String): Location? {
        val encoded = treeUri.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/')
        if (encoded.isEmpty()) return null
        val documentId = URLDecoder.decode(encoded, Charsets.UTF_8.name())
        if (':' !in documentId) return null
        return Location(documentId.substringAfter(':'), documentId.substringBefore(':'))
    }

    /** The last folder name, for a new pair's default name. */
    fun folderName(treeUri: String): String? =
        location(treeUri)
            ?.path
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
}
