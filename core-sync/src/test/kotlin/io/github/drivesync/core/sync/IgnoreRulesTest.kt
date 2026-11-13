package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.IgnoreRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IgnoreRulesTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "a.tmp",
            "a.TMP",
            "video.mp4.crdownload",
            "big.iso.part",
            "BIG.ISO.PART",
            ".photo.jpg.drivesync-part",
            "~\$report.docx",
            ".thumbnails",
            ".trashed-1700000000-photo.jpg",
            "sub/dir/a.tmp",
        ],
    )
    fun `ignored names`(raw: String) {
        assertTrue(IgnoreRules.isIgnored(RelPath.of(raw)), raw)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a.txt",
            "notes.tmp.txt",
            "part",
            "tmp",
            "thumbnails",
            ".thumbnails2",
            "trashed-1-x.jpg",
            "report~\$.docx",
            "a.drivesync-part.txt",
            "docs/.drivesync-recycle-not",
        ],
    )
    fun `regular names are not ignored`(raw: String) {
        assertFalse(IgnoreRules.isIgnored(RelPath.of(raw)), raw)
    }

    @ParameterizedTest
    @ValueSource(strings = [".thumbnails/x.jpg", "a/.thumbnails/b/c.jpg", "x.part/inner.txt", "~\$lock/a.txt"])
    fun `an ignored segment ignores the whole subtree`(raw: String) {
        assertTrue(IgnoreRules.isIgnored(RelPath.of(raw)), raw)
    }

    @ParameterizedTest
    @ValueSource(strings = [".drivesync-recycle", ".drivesync-recycle/a.txt", ".drivesync-recycle/deep/b/c.txt"])
    fun `the recycle folder at the pair root is ignored`(raw: String) {
        assertTrue(IgnoreRules.isIgnored(RelPath.of(raw)), raw)
    }

    @Test
    fun `temp path is a hidden sibling and is itself ignored`() {
        val temp = IgnoreRules.tempPathFor(RelPath.of("Photos/photo.jpg"))
        assertEquals(RelPath.of("Photos/.photo.jpg.drivesync-part"), temp)
        assertTrue(IgnoreRules.isIgnored(temp))
        assertEquals(RelPath.of(".a.drivesync-part"), IgnoreRules.tempPathFor(RelPath.of("a")))
    }
}
