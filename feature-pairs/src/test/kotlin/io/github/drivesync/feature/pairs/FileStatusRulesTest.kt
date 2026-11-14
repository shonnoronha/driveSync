package io.github.drivesync.feature.pairs

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.feature.pairs.files.FileStatus
import io.github.drivesync.feature.pairs.files.FileStatusRules
import io.github.drivesync.feature.pairs.files.RunIssues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileStatusRulesTest {
    private fun p(path: String) = RelPath.of(path)

    private fun file(
        path: String,
        size: Long = 10,
        mtime: Long = 1,
    ) = LocalEntry(p(path), isFolder = false, size = size, lastModified = mtime)

    private fun folder(path: String) = LocalEntry(p(path), isFolder = true)

    private fun base(
        path: String,
        size: Long = 10,
        mtime: Long = 1,
    ) = BaseEntry(p(path), isFolder = false, md5 = "x", localSize = size, localLastModified = mtime)

    @Test
    fun `phone files are compared only with their own base values`() {
        val index =
            FileStatusRules.index(
                local =
                    listOf(
                        file("same.jpg"),
                        file("edited.jpg", size = 11),
                        file("touched.jpg", mtime = 2),
                        file("new.jpg"),
                    ),
                base = listOf(base("same.jpg"), base("edited.jpg"), base("touched.jpg"), base("gone.jpg")),
            )
        val status = index.rowsIn(null).associate { it.name to it.status }
        assertEquals(
            mapOf(
                "edited.jpg" to FileStatus.CHANGED_ON_PHONE,
                "gone.jpg" to FileStatus.REMOVED_FROM_PHONE,
                "new.jpg" to FileStatus.NEW_ON_PHONE,
                "same.jpg" to FileStatus.SYNCED,
                "touched.jpg" to FileStatus.CHANGED_ON_PHONE,
            ),
            status,
        )
        assertEquals(5, index.totalFiles)
        assertEquals(4, index.notSynced)
    }

    @Test
    fun `before the first sync every phone file is new`() {
        val index = FileStatusRules.index(listOf(file("a.txt"), file("b.txt")), base = emptyList())
        assertEquals(mapOf(FileStatus.NEW_ON_PHONE to 2), index.counts)
    }

    @Test
    fun `folders count the files below them that are not synced`() {
        val index =
            FileStatusRules.index(
                local =
                    listOf(
                        folder("Photos"),
                        folder("Photos/2026"),
                        file("Photos/a.jpg"),
                        file("Photos/2026/b.jpg"),
                        file("Photos/2026/c.jpg"),
                        folder("Empty"),
                    ),
                base = listOf(base("Photos/a.jpg"), base("Photos/2026/b.jpg")),
            )
        val root = index.rowsIn(null)
        assertEquals(listOf("Empty", "Photos"), root.map { it.name })
        val photos = root.single { it.name == "Photos" }
        assertEquals(3, photos.files)
        assertEquals(1, photos.notSynced)
        assertEquals(0, root.single { it.name == "Empty" }.files)
        assertEquals(listOf("2026", "a.jpg"), index.rowsIn(p("Photos")).map { it.name })
        assertEquals(1, index.rowsIn(p("Photos")).single { it.name == "2026" }.notSynced)
    }

    @Test
    fun `only-not-synced hides synced files and fully synced folders`() {
        val index =
            FileStatusRules.index(
                local = listOf(folder("Done"), file("Done/a.jpg"), folder("Todo"), file("Todo/b.jpg"), file("c.jpg")),
                base = listOf(base("Done/a.jpg"), base("c.jpg")),
            )
        assertEquals(listOf("Todo"), index.notSyncedIn(null).map { it.name })
    }

    @Test
    fun `the latest run's conflicts, failures and skipped files win over the phone comparison`() {
        val index =
            FileStatusRules.index(
                local = listOf(file("clash.txt", size = 11), file("broken.jpg"), file("ok.jpg")),
                base = listOf(base("clash.txt"), base("ok.jpg")),
                issues =
                    RunIssues(
                        pendingConflicts = setOf("clash.txt"),
                        failed = mapOf("broken.jpg" to "Network error", "ok.jpg" to "Old error"),
                        skipped = setOf("Plan.gdoc"),
                    ),
            )
        val rows = index.rowsIn(null).associateBy { it.name }
        assertEquals(FileStatus.CONFLICT, rows.getValue("clash.txt").status)
        assertEquals(FileStatus.FAILED, rows.getValue("broken.jpg").status)
        assertEquals("Network error", rows.getValue("broken.jpg").detail)
        // Failed before but unchanged since the base was saved: it went through after all.
        assertEquals(FileStatus.SYNCED, rows.getValue("ok.jpg").status)
        assertEquals(FileStatus.SKIPPED, rows.getValue("Plan.gdoc").status)
    }

    @Test
    fun `temp downloads and the recycle folder are not listed`() {
        val index =
            FileStatusRules.index(
                local =
                    listOf(
                        file(".a.jpg.drivesync-part"),
                        folder(".drivesync-recycle"),
                        file(".drivesync-recycle/old.jpg"),
                        file("a.jpg"),
                    ),
                base = emptyList(),
            )
        assertEquals(listOf("a.jpg"), index.rowsIn(null).map { it.name })
    }
}
