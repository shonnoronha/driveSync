package io.github.drivesync.core.sync.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileFiltersTest {
    private fun p(path: String) = RelPath.of(path)

    private fun excludes(
        pattern: String,
        path: String,
    ) = FileFilters(exclude = listOf(pattern)).excludesPath(p(path))

    @Test
    fun `a name pattern matches at any depth`() {
        assertTrue(excludes("*.tmp", "a.tmp"))
        assertTrue(excludes("*.tmp", "Notes/2026/draft.TMP"))
        assertFalse(excludes("*.tmp", "tmp.txt"))
    }

    @Test
    fun `an excluded folder hides its contents`() {
        assertTrue(excludes("node_modules", "app/node_modules"))
        assertTrue(excludes("node_modules/", "app/node_modules/x/y.js"))
        assertFalse(excludes("node_modules", "app/node_modules_old/x.js"))
    }

    @Test
    fun `a pattern with a slash is anchored`() {
        assertTrue(excludes("Camera/old", "Camera/old/1.jpg"))
        assertTrue(excludes("/Camera/old/**", "Camera/old"))
        assertFalse(excludes("Camera/old", "Backup/Camera/old/1.jpg"))
        assertTrue(excludes("**/cache", "a/b/cache/x"))
        assertTrue(excludes("build/*.apk", "build/app.apk"))
        assertFalse(excludes("build/*.apk", "build/out/app.apk"))
    }

    @Test
    fun `question mark is one character`() {
        assertTrue(excludes("IMG_?.jpg", "IMG_1.jpg"))
        assertFalse(excludes("IMG_?.jpg", "IMG_12.jpg"))
    }

    @Test
    fun `blank and comment lines are ignored`() {
        val f = FileFilters(exclude = listOf("", "  ", "# notes", "/"))
        assertTrue(f.isEmpty)
        assertFalse(f.excludesPath(p("a.txt")))
    }

    @Test
    fun `hidden names can be skipped`() {
        val f = FileFilters(skipHidden = true)
        assertTrue(f.excludesPath(p(".obsidian/workspace.json")))
        assertTrue(f.excludesPath(p("notes/.DS_Store")))
        assertFalse(f.excludesPath(p("notes/a.md")))
    }

    @Test
    fun `size limit applies to files on either side`() {
        val f = FileFilters(maxFileSize = 10)
        val local = listOf(LocalEntry(p("big.mp4"), isFolder = false, size = 11))
        val remote = listOf(RemoteEntry(p("big.mp4"), "r1", isFolder = false, size = 5))
        assertEquals(setOf(p("big.mp4")), f.excludedPaths(local, remote))
        assertFalse(f.excludes(p("Videos"), isFolder = true, size = 999))
    }

    @Test
    fun `top level only`() {
        val f = FileFilters(includeSubfolders = false)
        assertFalse(f.isEmpty)
        assertFalse(f.excludes(p("a.md"), isFolder = false, size = 1))
        assertTrue(f.excludes(p("Sub"), isFolder = true, size = 0))
        assertTrue(f.excludesPath(p("Sub/a.md")))
        val base = listOf(BaseEntry(p("Old"), isFolder = true))
        assertEquals(setOf(p("Old")), f.excludedPaths(emptyList(), emptyList(), base))
    }
}
