package io.github.drivesync.core.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MediaFoldersTest {
    @ParameterizedTest
    @CsvSource(
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM, true",
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera, true",
        "content://com.android.externalstorage.documents/tree/primary%3APictures%2FScreenshots, true",
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMovies, true",
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes, false",
        "content://com.android.externalstorage.documents/tree/primary%3ANotes%2FDCIM, false",
        "content://com.android.externalstorage.documents/tree/primary%3A, false",
        "content://other/document/x, false",
    )
    fun `media folders are DCIM, Pictures and Movies`(
        uri: String,
        media: Boolean,
    ) {
        assertEquals(media, MediaFolders.isMediaTree(uri))
    }
}
