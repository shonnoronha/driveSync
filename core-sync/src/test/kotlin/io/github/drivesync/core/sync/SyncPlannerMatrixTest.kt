package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.ForgetBase
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** PRD 3.3 decision matrix, row by row, for a TWO_WAY pair with the default (keep-both) policy. */
class SyncPlannerMatrixTest {
    private val a = path("a.txt")
    private val base = listOf(base("a.txt", "v1"))

    @Nested
    inner class UnchangedUnchanged {
        @Test
        fun `does nothing`() {
            val f = synced("a.txt", "v1")
            assertNothingToDo(planReady(listOf(f.local), listOf(f.remote), listOf(f.base)))
        }

        @Test
        fun `touched local file with the same hash only refreshes the base`() {
            val l = local("a.txt", "v1", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v1")
            val plan = planReady(listOf(l), listOf(r), base)
            assertActions(plan, Link(a, l, r))
            assertConflicts(plan)
        }

        @Test
        fun `new Drive version with the same md5 only refreshes the base`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v1", version = 7)
            assertActions(planReady(listOf(l), listOf(r), base), Link(a, l, r))
        }

        @Test
        fun `Drive file replaced by a new id with the same content only refreshes the base`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v1", fileId = "replacement")
            assertActions(planReady(listOf(l), listOf(r), base), Link(a, l, r))
        }
    }

    @Nested
    inner class ChangedLocally {
        @Test
        fun `modified locally uploads over the Drive file`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v1")
            val plan = planReady(listOf(l), listOf(r), base)
            assertActions(plan, Upload(a, l, replaces = r))
            assertConflicts(plan)
        }

        @Test
        fun `modified locally without a hash still uploads`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME, hashed = false)
            val r = remote("a.txt", "v1")
            assertActions(planReady(listOf(l), listOf(r), base), Upload(a, l, replaces = r))
        }

        @Test
        fun `new local file uploads as a new Drive file`() {
            val l = local("a.txt", "v1")
            assertActions(planReady(listOf(l), emptyList(), emptyList()), Upload(a, l, replaces = null))
        }

        @Test
        fun `new local file in a nested folder that exists on both sides`() {
            val l = local("d/a.txt", "v1")
            val plan =
                planReady(
                    listOf(localFolder("d"), l),
                    listOf(remoteFolder("d")),
                    listOf(baseFolder("d")),
                )
            assertActions(plan, Upload(path("d/a.txt"), l, replaces = null))
        }
    }

    @Nested
    inner class ChangedOnDrive {
        @Test
        fun `modified on Drive downloads over the local file`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v2", version = 2)
            val plan = planReady(listOf(l), listOf(r), base)
            assertActions(plan, Download(a, r, replaces = l))
            assertConflicts(plan)
        }

        @Test
        fun `modified on Drive without md5 is detected by version`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v1", version = 2, hashed = false)
            assertActions(planReady(listOf(l), listOf(r), base), Download(a, r, replaces = l))
        }

        @Test
        fun `new Drive file downloads as a new local file`() {
            val r = remote("a.txt", "v1")
            assertActions(planReady(emptyList(), listOf(r), emptyList()), Download(a, r, replaces = null))
        }

        @Test
        fun `touched local file and modified Drive file downloads`() {
            val l = local("a.txt", "v1", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v2", version = 2)
            assertActions(planReady(listOf(l), listOf(r), base), Download(a, r, replaces = l))
        }
    }

    @Nested
    inner class Deletes {
        @Test
        fun `deleted locally, unchanged on Drive - trash on Drive`() {
            val r = remote("a.txt", "v1")
            val plan = planReady(emptyList(), listOf(r), base)
            assertActions(plan, TrashRemote(a, r))
            assertConflicts(plan)
        }

        @Test
        fun `unchanged locally, deleted on Drive - recycle locally`() {
            val l = local("a.txt", "v1")
            val plan = planReady(listOf(l), emptyList(), base)
            assertActions(plan, RecycleLocal(a, l))
            assertConflicts(plan)
        }

        @Test
        fun `deleted on both sides - only forget the base`() {
            assertActions(planReady(emptyList(), emptyList(), base), ForgetBase(a))
        }
    }

    @Nested
    inner class BothModified {
        @Test
        fun `different content keeps both - Drive version becomes the conflict copy`() {
            val l = local("a.txt", "local edit", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "drive edit", version = 2)
            val copy = conflictCopy("a.txt")
            val plan = planReady(listOf(l), listOf(r), base)
            assertActions(
                plan,
                MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY),
                Upload(a, l, replaces = null),
                Download(copy, r.copy(path = copy), replaces = null),
            )
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `same new content on both sides links without transfer`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v2", version = 2)
            val plan = planReady(listOf(l), listOf(r), base)
            assertActions(plan, Link(a, l, r))
            assertConflicts(plan)
        }

        @Test
        fun `local hash unknown is treated as a conflict, never as a link`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME, hashed = false)
            val r = remote("a.txt", "v2", version = 2)
            val plan = planReady(listOf(l), listOf(r), base)
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.KEPT_BOTH, conflictCopy("a.txt")),
            )
        }
    }

    @Nested
    inner class ModifyDelete {
        @Test
        fun `modified locally, deleted on Drive - keep the local file and re-upload`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            val plan = planReady(listOf(l), emptyList(), base)
            assertActions(plan, Upload(a, l, replaces = null))
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @Test
        fun `deleted locally, modified on Drive - keep the Drive file and re-download`() {
            val r = remote("a.txt", "v2", version = 2)
            val plan = planReady(emptyList(), listOf(r), base)
            assertActions(plan, Download(a, r, replaces = null))
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, ConflictResolution.KEPT_MODIFIED),
            )
        }
    }

    @Nested
    inner class BothNew {
        @Test
        fun `same name and same hash - link, no transfer`() {
            val l = local("a.txt", "same")
            val r = remote("a.txt", "same")
            val plan = planReady(listOf(l), listOf(r), emptyList())
            assertActions(plan, Link(a, l, r))
            assertConflicts(plan)
        }

        @Test
        fun `same name and different hash - keep-both conflict`() {
            val l = local("a.txt", "mine")
            val r = remote("a.txt", "theirs")
            val copy = conflictCopy("a.txt")
            val plan = planReady(listOf(l), listOf(r), emptyList())
            assertActions(
                plan,
                MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY),
                Upload(a, l, replaces = null),
                Download(copy, r.copy(path = copy), replaces = null),
            )
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_NEW, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `same name without a local hash is a conflict`() {
            val l = local("a.txt", "same", hashed = false)
            val r = remote("a.txt", "same")
            val plan = planReady(listOf(l), listOf(r), emptyList())
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.BOTH_NEW, ConflictResolution.KEPT_BOTH, conflictCopy("a.txt")),
            )
        }
    }

    @Test
    fun `several files are planned independently in one run`() {
        val up = local("up.txt", "new", lastModified = EDITED_MTIME)
        val upR = remote("up.txt", "old")
        val down = local("down.txt", "old")
        val downR = remote("down.txt", "new", version = 2)
        val plan =
            planReady(
                listOf(up, down),
                listOf(upR, downR),
                listOf(base("up.txt", "old"), base("down.txt", "old")),
            )
        assertActions(plan, Upload(path("up.txt"), up, replaces = upR), Download(path("down.txt"), downR, down))
    }
}
