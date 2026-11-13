package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SyncAction.CreateLocalFolder
import io.github.drivesync.core.sync.plan.SyncAction.CreateRemoteFolder
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.ForgetBase
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.MoveLocal
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Rename and move detection (PRD 3.9, FR-S5). */
class SyncPlannerMoveTest {
    private val old = path("old.txt")
    private val new = path("new.txt")
    private val oldBase = base("old.txt", "content")

    @Nested
    inner class LocalMove {
        private val r = remote("old.txt", "content")
        private val moved = local("new.txt", "content", lastModified = EDITED_MTIME)

        @Test
        fun `renamed on the phone renames the Drive file`() {
            val plan = planReady(listOf(moved), listOf(r), listOf(oldBase))
            assertActions(plan, MoveRemote(new, old, r, MoveReason.DETECTED_MOVE))
            assertConflicts(plan)
        }

        @Test
        fun `upload-only pairs detect local moves too`() {
            val plan = planReady(listOf(moved), listOf(r), listOf(oldBase), settings(SyncDirection.UPLOAD_ONLY))
            assertActions(plan, MoveRemote(new, old, r, MoveReason.DETECTED_MOVE))
        }

        @Test
        fun `download-only pairs ignore local moves and restore the file`() {
            val plan = planReady(listOf(moved), listOf(r), listOf(oldBase), settings(SyncDirection.DOWNLOAD_ONLY))
            assertActions(plan, Download(old, r, replaces = null))
        }

        @Test
        fun `move into a new subfolder creates the Drive folder first`() {
            val into = local("sub/old.txt", "content")
            val plan = planReady(listOf(localFolder("sub"), into), listOf(r), listOf(oldBase))
            assertActions(
                plan,
                CreateRemoteFolder(path("sub")),
                MoveRemote(path("sub/old.txt"), old, r, MoveReason.DETECTED_MOVE),
            )
        }

        @Test
        fun `a new file without a hash is not matched`() {
            val unhashed = local("new.txt", "content", hashed = false)
            val plan = planReady(listOf(unhashed), listOf(r), listOf(oldBase))
            assertActions(plan, Upload(new, unhashed, replaces = null), TrashRemote(old, r))
        }

        @Test
        fun `same size but different content is not a move`() {
            val other = local("new.txt", "CONTENT")
            val plan = planReady(listOf(other), listOf(r), listOf(oldBase))
            assertActions(plan, Upload(new, other, replaces = null), TrashRemote(old, r))
        }

        @Test
        fun `two candidates with the same content are ambiguous`() {
            val twin = local("twin.txt", "content")
            val plan = planReady(listOf(moved, twin), listOf(r), listOf(oldBase))
            assertActions(
                plan,
                Upload(new, moved, replaces = null),
                Upload(path("twin.txt"), twin, replaces = null),
                TrashRemote(old, r),
            )
        }

        @Test
        fun `two vanished files with the same content are ambiguous`() {
            val r2 = remote("old2.txt", "content")
            val plan = planReady(listOf(moved), listOf(r, r2), listOf(oldBase, base("old2.txt", "content")))
            assertActions(
                plan,
                Upload(new, moved, replaces = null),
                TrashRemote(old, r),
                TrashRemote(path("old2.txt"), r2),
            )
        }

        @Test
        fun `a Drive edit to the old file blocks the move`() {
            val edited = remote("old.txt", "content", version = 2, hashed = false)
            val plan = planReady(listOf(moved), listOf(edited), listOf(oldBase))
            assertActions(plan, Upload(new, moved, replaces = null), Download(old, edited, replaces = null))
            assertConflicts(
                plan,
                Conflict(old, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @Test
        fun `a target that already exists on Drive is not a move target`() {
            val there = remote("new.txt", "content")
            val plan = planReady(listOf(moved), listOf(r, there), listOf(oldBase))
            assertActions(plan, Link(new, moved, there), TrashRemote(old, r))
        }

        @Test
        fun `several independent moves in one run`() {
            val r2 = remote("b.txt", "bee")
            val moved2 = local("dir/b.txt", "bee")
            val plan =
                planReady(
                    listOf(moved, moved2, localFolder("dir")),
                    listOf(r, r2),
                    listOf(oldBase, base("b.txt", "bee")),
                )
            assertEquals(
                listOf("CreateRemoteFolder:dir", "MoveRemote:dir/b.txt", "MoveRemote:new.txt"),
                plan.summary(),
            )
        }
    }

    @Nested
    inner class RemoteMove {
        private val l = local("old.txt", "content")
        private val movedR = remote("new.txt", "content", fileId = "id:old.txt", version = 2)

        @Test
        fun `renamed on Drive renames the local file`() {
            val plan = planReady(listOf(l), listOf(movedR), listOf(oldBase))
            assertActions(plan, MoveLocal(new, old, l, MoveReason.DETECTED_MOVE, movedR))
            assertConflicts(plan)
        }

        @Test
        fun `download-only pairs detect Drive moves too`() {
            val plan = planReady(listOf(l), listOf(movedR), listOf(oldBase), settings(SyncDirection.DOWNLOAD_ONLY))
            assertActions(plan, MoveLocal(new, old, l, MoveReason.DETECTED_MOVE, movedR))
        }

        @Test
        fun `upload-only pairs ignore Drive moves and restore the backup`() {
            val plan = planReady(listOf(l), listOf(movedR), listOf(oldBase), settings(SyncDirection.UPLOAD_ONLY))
            assertActions(plan, Upload(old, l, replaces = null))
        }

        @Test
        fun `move into a new Drive folder creates the local folder first`() {
            val deeper = movedR.copy(path = path("sub/old.txt"))
            val plan = planReady(listOf(l), listOf(remoteFolder("sub"), deeper), listOf(oldBase))
            assertActions(
                plan,
                CreateLocalFolder(path("sub")),
                MoveLocal(path("sub/old.txt"), old, l, MoveReason.DETECTED_MOVE, deeper),
            )
        }

        @Test
        fun `a local edit blocks the move and both versions survive`() {
            val edited = local("old.txt", "phone edit", lastModified = EDITED_MTIME)
            val plan = planReady(listOf(edited), listOf(movedR), listOf(oldBase))
            assertActions(plan, Upload(old, edited, replaces = null), Download(new, movedR, replaces = null))
            assertConflicts(
                plan,
                Conflict(old, ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @Test
        fun `a Drive content change blocks the move`() {
            val changed = remote("new.txt", "changed", fileId = "id:old.txt", version = 3)
            val plan = planReady(listOf(l), listOf(changed), listOf(oldBase))
            assertActions(plan, Download(new, changed, replaces = null), RecycleLocal(old, l))
        }

        @Test
        fun `a target that already exists locally blocks the move`() {
            val blocker = local("new.txt", "other")
            val plan = planReady(listOf(l, blocker), listOf(movedR), listOf(oldBase))
            val kinds = plan.actions.map { it::class.simpleName }
            assertEquals(listOf("MoveRemote", "Upload", "Download", "RecycleLocal"), kinds)
        }

        @Test
        fun `Drive folder rename moves each file and replaces the folder`() {
            val x = local("A/x.txt", "x")
            val movedX = remote("B/x.txt", "x", fileId = "id:A/x.txt")
            val plan =
                planReady(
                    listOf(localFolder("A"), x),
                    listOf(remoteFolder("B", fileId = "id:A"), movedX),
                    listOf(baseFolder("A"), base("A/x.txt", "x")),
                )
            assertActions(
                plan,
                CreateLocalFolder(path("B")),
                MoveLocal(path("B/x.txt"), path("A/x.txt"), x, MoveReason.DETECTED_MOVE, movedX),
                RecycleLocal(path("A"), localFolder("A")),
            )
        }
    }

    @Test
    fun `local folder rename moves each Drive file and replaces the folder`() {
        val ra = remoteFolder("A")
        val plan =
            planReady(
                listOf(localFolder("B"), local("B/x.txt", "x")),
                listOf(ra, remote("A/x.txt", "x")),
                listOf(baseFolder("A"), base("A/x.txt", "x")),
            )
        assertActions(
            plan,
            CreateRemoteFolder(path("B")),
            MoveRemote(path("B/x.txt"), path("A/x.txt"), remote("A/x.txt", "x"), MoveReason.DETECTED_MOVE),
            TrashRemote(path("A"), ra),
        )
    }

    @Test
    fun `base entry of a move is never forgotten separately`() {
        val plan =
            planReady(
                listOf(local("new.txt", "content")),
                listOf(remote("old.txt", "content")),
                listOf(oldBase),
            )
        assertEquals(0, plan.actions.count { it is ForgetBase })
    }
}
