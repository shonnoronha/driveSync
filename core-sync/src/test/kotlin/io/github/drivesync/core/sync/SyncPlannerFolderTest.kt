package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SkipReason
import io.github.drivesync.core.sync.plan.Skipped
import io.github.drivesync.core.sync.plan.SyncAction.CreateLocalFolder
import io.github.drivesync.core.sync.plan.SyncAction.CreateRemoteFolder
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.ForgetBase
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Folders are existence-only (FR-S2) and a folder delete never takes a surviving file with it. */
class SyncPlannerFolderTest {
    private val d = path("d")
    private val ld = localFolder("d")
    private val rd = remoteFolder("d")
    private val bd = baseFolder("d")

    @Nested
    inner class Creation {
        @Test
        fun `new local folder is created on Drive`() {
            assertActions(planReady(listOf(ld), emptyList(), emptyList()), CreateRemoteFolder(d))
        }

        @Test
        fun `new Drive folder is created locally`() {
            assertActions(planReady(emptyList(), listOf(rd), emptyList()), CreateLocalFolder(d))
        }

        @Test
        fun `folder new on both sides is linked`() {
            assertActions(planReady(listOf(ld), listOf(rd), null), Link(d, ld, rd))
        }

        @Test
        fun `folder in sync does nothing`() {
            assertNothingToDo(planReady(listOf(ld), listOf(rd), listOf(bd)))
        }

        @Test
        fun `nested new folders are created parents first`() {
            val plan = planReady(listOf(localFolder("a/b/c"), localFolder("a"), localFolder("a/b")), emptyList(), null)
            assertActions(
                plan,
                CreateRemoteFolder(path("a")),
                CreateRemoteFolder(path("a/b")),
                CreateRemoteFolder(path("a/b/c")),
            )
        }

        @Test
        fun `new folder with a file creates the folder before the upload`() {
            val x = local("d/x.txt", "x")
            val plan = planReady(listOf(ld, x), emptyList(), emptyList())
            assertActions(plan, CreateRemoteFolder(d), Upload(x.path, x, null))
        }
    }

    @Nested
    inner class DeletedLocally {
        @Test
        fun `empty folder deleted on the phone is trashed on Drive`() {
            assertActions(planReady(emptyList(), listOf(rd), listOf(bd)), TrashRemote(d, rd))
        }

        @Test
        fun `folder and its files are trashed deepest first`() {
            val x = synced("d/x.txt", "x")
            val e = synced("d/e/y.txt", "y")
            val re = remoteFolder("d/e")
            val plan =
                planReady(
                    emptyList(),
                    listOf(rd, re, x.remote, e.remote),
                    listOf(bd, baseFolder("d/e"), x.base, e.base),
                )
            assertActions(
                plan,
                TrashRemote(e.remote.path, e.remote),
                TrashRemote(path("d/e"), re),
                TrashRemote(x.remote.path, x.remote),
                TrashRemote(d, rd),
            )
        }

        @Test
        fun `a new Drive file inside keeps the folder and comes down`() {
            val fresh = remote("d/new.txt", "n")
            val plan = planReady(emptyList(), listOf(rd, fresh), listOf(bd))
            assertActions(plan, CreateLocalFolder(d), Download(fresh.path, fresh, replaces = null))
        }

        @Test
        fun `an edited Drive file inside keeps the folder`() {
            val edited = remote("d/x.txt", "v2", version = 2)
            val plan = planReady(emptyList(), listOf(rd, edited), listOf(bd, base("d/x.txt", "v1")))
            assertActions(plan, CreateLocalFolder(d), Download(edited.path, edited, replaces = null))
            assertConflicts(
                plan,
                Conflict(edited.path, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @Test
        fun `a Google Doc inside keeps the Drive folder`() {
            val doc = googleNative("d/Notes")
            val plan = planReady(emptyList(), listOf(rd, doc), listOf(bd))
            assertActions(plan, CreateLocalFolder(d))
            assertSkipped(plan, Skipped(doc.path, SkipReason.GOOGLE_NATIVE))
        }

        @Test
        fun `an ignored Drive file inside keeps the Drive folder`() {
            assertActions(planReady(emptyList(), listOf(rd, remote("d/x.tmp", "t")), listOf(bd)), CreateLocalFolder(d))
        }
    }

    @Nested
    inner class DeletedOnDrive {
        @Test
        fun `empty folder deleted on Drive is recycled locally`() {
            assertActions(planReady(listOf(ld), emptyList(), listOf(bd)), RecycleLocal(d, ld))
        }

        @Test
        fun `folder and its unchanged files are recycled deepest first`() {
            val x = synced("d/x.txt", "x")
            val plan = planReady(listOf(ld, x.local), emptyList(), listOf(bd, x.base))
            assertActions(plan, RecycleLocal(x.local.path, x.local), RecycleLocal(d, ld))
        }

        @Test
        fun `a new local file inside keeps the folder and goes up`() {
            val fresh = local("d/new.txt", "n")
            val plan = planReady(listOf(ld, fresh), emptyList(), listOf(bd))
            assertActions(plan, CreateRemoteFolder(d), Upload(fresh.path, fresh, replaces = null))
        }

        @Test
        fun `a new local subfolder inside recreates both folders`() {
            val sub = localFolder("d/sub")
            val plan = planReady(listOf(ld, sub), emptyList(), listOf(bd))
            assertActions(plan, CreateRemoteFolder(d), CreateRemoteFolder(sub.path))
        }

        @Test
        fun `an edited local file inside keeps the folder`() {
            val edited = local("d/x.txt", "v2", lastModified = EDITED_MTIME)
            val plan = planReady(listOf(ld, edited), emptyList(), listOf(bd, base("d/x.txt", "v1")))
            assertActions(plan, CreateRemoteFolder(d), Upload(edited.path, edited, replaces = null))
        }

        @Test
        fun `an ignored local file inside keeps the local folder`() {
            val partial = local("d/.x.jpg.drivesync-part", "half")
            assertActions(planReady(listOf(ld, partial), emptyList(), listOf(bd)), CreateRemoteFolder(d))
        }
    }

    @Test
    fun `folder deleted on both sides is forgotten`() {
        assertActions(planReady(emptyList(), emptyList(), listOf(bd)), ForgetBase(d))
    }

    @Nested
    inner class UploadOnly {
        private val up = settings(SyncDirection.UPLOAD_ONLY)
        private val mirror = settings(SyncDirection.UPLOAD_ONLY, mirrorDeletions = true)

        @Test
        fun `Drive-only folder is left alone`() {
            assertNothingToDo(planReady(emptyList(), listOf(rd), emptyList(), up))
        }

        @Test
        fun `folder deleted on Drive is recreated`() {
            assertActions(planReady(listOf(ld), emptyList(), listOf(bd), up), CreateRemoteFolder(d))
        }

        @Test
        fun `local folder delete is not mirrored by default`() {
            assertActions(planReady(emptyList(), listOf(rd), listOf(bd), up), ForgetBase(d))
        }

        @Test
        fun `local folder delete is mirrored when asked`() {
            assertActions(planReady(emptyList(), listOf(rd), listOf(bd), mirror), TrashRemote(d, rd))
        }

        @Test
        fun `mirrored folder delete keeps a folder with Drive-only files`() {
            val driveOnly = remote("d/drive-only.txt", "x")
            assertActions(planReady(emptyList(), listOf(rd, driveOnly), listOf(bd), mirror), ForgetBase(d))
        }
    }

    @Nested
    inner class DownloadOnly {
        private val down = settings(SyncDirection.DOWNLOAD_ONLY)
        private val mirror = settings(SyncDirection.DOWNLOAD_ONLY, mirrorDeletions = true)

        @Test
        fun `phone-only folder is left alone`() {
            assertNothingToDo(planReady(listOf(ld), emptyList(), emptyList(), down))
        }

        @Test
        fun `folder deleted on the phone is recreated`() {
            assertActions(planReady(emptyList(), listOf(rd), listOf(bd), down), CreateLocalFolder(d))
        }

        @Test
        fun `Drive folder delete is not mirrored by default`() {
            assertActions(planReady(listOf(ld), emptyList(), listOf(bd), down), ForgetBase(d))
        }

        @Test
        fun `Drive folder delete is mirrored when asked`() {
            assertActions(planReady(listOf(ld), emptyList(), listOf(bd), mirror), RecycleLocal(d, ld))
        }

        @Test
        fun `mirrored folder delete keeps a folder with phone-only files`() {
            val phoneOnly = local("d/phone-only.txt", "x")
            assertActions(planReady(listOf(ld, phoneOnly), emptyList(), listOf(bd), mirror), ForgetBase(d))
        }
    }
}
