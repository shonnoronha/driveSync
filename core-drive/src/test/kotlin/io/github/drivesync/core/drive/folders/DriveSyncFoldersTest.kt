package io.github.drivesync.core.drive.folders

import io.github.drivesync.core.drive.FakeDriveServer
import io.github.drivesync.core.drive.FakeDriveServer.Companion.MY_DRIVE
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DriveSyncFoldersTest {
    private val drive = FakeDriveServer()
    private val folders = DriveSyncFolders(drive.client())
    private val rootTag = mapOf("role" to "root")

    @AfterEach
    fun tearDown() = drive.close()

    @Nested
    inner class Root {
        @Test
        fun `first sign-in creates a tagged Drive Sync folder in My Drive`() =
            runTest {
                val id = folders.findOrCreateRoot()
                val root = drive.node(id)
                assertEquals("Drive Sync", root.name)
                assertEquals(listOf(MY_DRIVE), root.parents)
                assertEquals(rootTag, root.appProperties)
            }

        @Test
        fun `a reinstall or second phone reuses the existing root`() =
            runTest {
                val first = folders.findOrCreateRoot()
                assertEquals(first, DriveSyncFolders(drive.client()).findOrCreateRoot())
                assertEquals(1, drive.children(MY_DRIVE).size)
            }

        @Test
        fun `the root is found by tag, even renamed and moved`() =
            runTest {
                val elsewhere = drive.folder("Backups")
                val id = drive.folder("My sync", elsewhere, rootTag)
                assertEquals(id, folders.findOrCreateRoot())
            }

        @Test
        fun `an untagged folder named Drive Sync is not the root`() =
            runTest {
                val impostor = drive.folder("Drive Sync")
                assertNotEquals(impostor, folders.findOrCreateRoot())
            }

        @Test
        fun `a trashed root is not found`() =
            runTest {
                drive.trash(drive.folder("Drive Sync", appProperties = rootTag))
                assertNull(folders.findRoot())
            }

        @Test
        fun `when two phones raced, everyone picks the oldest root`() =
            runTest {
                val older = drive.folder("Drive Sync", appProperties = rootTag)
                drive.folder("Drive Sync", appProperties = rootTag)
                assertEquals(older, folders.findRoot()!!.id)
            }
    }

    @Nested
    inner class Status {
        @Test
        fun `reports ok, trashed and missing without fixing anything`() =
            runTest {
                val ok = drive.folder("a")
                val trashed = drive.folder("b").also { drive.trash(it) }
                val gone = drive.folder("c").also { drive.delete(it) }
                val file = drive.file("d", MY_DRIVE)
                assertEquals(FolderStatus.OK, folders.status(ok))
                assertEquals(FolderStatus.TRASHED, folders.status(trashed))
                assertEquals(FolderStatus.MISSING, folders.status(gone))
                assertEquals(FolderStatus.MISSING, folders.status(file))
                assertEquals(0, drive.requestCount("POST", "/drive/v3/files"))
            }
    }

    @Nested
    inner class Pairs {
        @Test
        fun `a pair folder is named after the local folder and tagged`() =
            runTest {
                val root = folders.findOrCreateRoot()
                val pair = folders.createPairFolder(root, "Camera", "uuid-1")
                assertEquals(PairFolder(pair.folderId, "Camera", "uuid-1"), pair)
                val node = drive.node(pair.folderId)
                assertEquals(listOf(root), node.parents)
                assertEquals(mapOf("role" to "pair", "pairId" to "uuid-1"), node.appProperties)
            }

        @Test
        fun `name clashes get a number`() =
            runTest {
                val root = folders.findOrCreateRoot()
                drive.file("Notes (2)", root)
                val names = (1..3).map { folders.createPairFolder(root, "Notes", "u$it").name }
                assertEquals(listOf("Notes", "Notes (3)", "Notes (4)"), names)
            }

        @Test
        fun `a blank local name falls back to a default`() =
            runTest {
                val root = folders.findOrCreateRoot()
                assertEquals("Folder", folders.createPairFolder(root, " ", "u").name)
            }

        @Test
        fun `existing pair folders are offered for linking`() =
            runTest {
                val root = folders.findOrCreateRoot()
                folders.createPairFolder(root, "Zeta", "u2")
                folders.createPairFolder(root, "Alpha", "u1")
                drive.folder("untagged", root)
                drive.folder("lost tag", root, mapOf("role" to "pair"))
                drive.trash(folders.createPairFolder(root, "Old", "u3").folderId)
                assertEquals(
                    listOf("Alpha" to "u1", "Zeta" to "u2", "lost tag" to null),
                    folders.pairFolders(root).map { it.name to it.pairId },
                )
            }

        @Test
        fun `unique names skip every taken one`() {
            assertEquals("a (3)", DriveSyncFolders.uniqueName("a", setOf("a", "a (2)")))
            assertEquals("a", DriveSyncFolders.uniqueName("a", emptySet()))
        }
    }
}
