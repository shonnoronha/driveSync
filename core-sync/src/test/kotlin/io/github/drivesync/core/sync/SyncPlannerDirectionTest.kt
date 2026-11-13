package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.ForgetBase
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.MoveLocal
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** One-way pairs (PRD FR-P3, 3.9 "direction semantics"). */
class SyncPlannerDirectionTest {
    private val a = path("a.txt")
    private val base = listOf(base("a.txt", "v1"))
    private val copy = conflictCopy("a.txt")

    @Nested
    inner class UploadOnly {
        private val up = settings(SyncDirection.UPLOAD_ONLY)
        private val mirror = settings(SyncDirection.UPLOAD_ONLY, mirrorDeletions = true)

        @Test
        fun `in sync does nothing`() {
            val f = synced("a.txt", "v1")
            assertNothingToDo(planReady(listOf(f.local), listOf(f.remote), listOf(f.base), up))
        }

        @Test
        fun `touched file refreshes the base`() {
            val l = local("a.txt", "v1", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v1")
            assertActions(planReady(listOf(l), listOf(r), base, up), Link(a, l, r))
        }

        @Test
        fun `local edit uploads`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v1")
            assertActions(planReady(listOf(l), listOf(r), base, up), Upload(a, l, replaces = r))
        }

        @Test
        fun `new local file uploads`() {
            val l = local("a.txt", "v1")
            assertActions(planReady(listOf(l), emptyList(), emptyList(), up), Upload(a, l, replaces = null))
        }

        @Test
        fun `file deleted on Drive is restored from the phone`() {
            val l = local("a.txt", "v1")
            val plan = planReady(listOf(l), emptyList(), base, up)
            assertActions(plan, Upload(a, l, replaces = null))
            assertConflicts(plan)
        }

        @Test
        fun `file edited locally and deleted on Drive is uploaded`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            assertActions(planReady(listOf(l), emptyList(), base, up), Upload(a, l, replaces = null))
        }

        @Test
        fun `Drive-only new file is left alone`() {
            assertNothingToDo(planReady(emptyList(), listOf(remote("a.txt", "v1")), emptyList(), up))
        }

        @Test
        fun `local delete is not propagated without mirror deletions`() {
            assertActions(planReady(emptyList(), listOf(remote("a.txt", "v1")), base, up), ForgetBase(a))
        }

        @Test
        fun `local delete is trashed on Drive with mirror deletions`() {
            val r = remote("a.txt", "v1")
            assertActions(planReady(emptyList(), listOf(r), base, mirror), TrashRemote(a, r))
        }

        @Test
        fun `mirror deletions never trashes a file edited on Drive`() {
            val r = remote("a.txt", "v2", version = 2)
            val plan = planReady(emptyList(), listOf(r), base, mirror)
            assertActions(plan, ForgetBase(a))
            assertConflicts(plan)
        }

        @Test
        fun `deleted on both sides forgets the base`() {
            assertActions(planReady(emptyList(), emptyList(), base, mirror), ForgetBase(a))
        }

        @Test
        fun `Drive edit is kept as a conflict copy on Drive and the phone version wins the name`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "drive edit", version = 2)
            val plan = planReady(listOf(l), listOf(r), base, up)
            assertActions(plan, MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY), Upload(a, l, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.DESTINATION_MODIFIED, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `edited on both sides keeps the Drive version as a copy`() {
            val l = local("a.txt", "phone edit", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "drive edit", version = 2)
            val plan = planReady(listOf(l), listOf(r), base, up)
            assertActions(plan, MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY), Upload(a, l, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `new on both sides with different content`() {
            val l = local("a.txt", "phone")
            val r = remote("a.txt", "drive")
            val plan = planReady(listOf(l), listOf(r), null, up)
            assertActions(plan, MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY), Upload(a, l, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_NEW, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `new on both sides with the same content links`() {
            val l = local("a.txt", "same")
            val r = remote("a.txt", "same")
            assertActions(planReady(listOf(l), listOf(r), null, up), Link(a, l, r))
        }

        @Test
        fun `edited on both sides to the same content links`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v2", version = 2)
            assertActions(planReady(listOf(l), listOf(r), base, up), Link(a, l, r))
        }

        @Test
        fun `ask policy leaves a diverged destination pending`() {
            val r = remote("a.txt", "drive edit", version = 2)
            val ask = PairSettings(direction = SyncDirection.UPLOAD_ONLY, conflictPolicy = ConflictPolicy.ASK)
            val plan = planReady(listOf(local("a.txt", "v1")), listOf(r), base, ask)
            assertActions(plan)
            assertConflicts(plan, Conflict(a, ConflictKind.DESTINATION_MODIFIED, ConflictResolution.PENDING))
        }
    }

    @Nested
    inner class DownloadOnly {
        private val down = settings(SyncDirection.DOWNLOAD_ONLY)
        private val mirror = settings(SyncDirection.DOWNLOAD_ONLY, mirrorDeletions = true)

        @Test
        fun `in sync does nothing`() {
            val f = synced("a.txt", "v1")
            assertNothingToDo(planReady(listOf(f.local), listOf(f.remote), listOf(f.base), down))
        }

        @Test
        fun `Drive edit downloads`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v2", version = 2)
            assertActions(planReady(listOf(l), listOf(r), base, down), Download(a, r, replaces = l))
        }

        @Test
        fun `new Drive file downloads`() {
            val r = remote("a.txt", "v1")
            assertActions(planReady(emptyList(), listOf(r), emptyList(), down), Download(a, r, replaces = null))
        }

        @Test
        fun `file deleted on the phone is downloaded again`() {
            val r = remote("a.txt", "v1")
            val plan = planReady(emptyList(), listOf(r), base, down)
            assertActions(plan, Download(a, r, replaces = null))
            assertConflicts(plan)
        }

        @Test
        fun `file deleted on the phone and edited on Drive is downloaded`() {
            val r = remote("a.txt", "v2", version = 2)
            assertActions(planReady(emptyList(), listOf(r), base, down), Download(a, r, replaces = null))
        }

        @Test
        fun `phone-only new file is left alone`() {
            assertNothingToDo(planReady(listOf(local("a.txt", "v1")), emptyList(), emptyList(), down))
        }

        @Test
        fun `Drive delete is not propagated without mirror deletions`() {
            assertActions(planReady(listOf(local("a.txt", "v1")), emptyList(), base, down), ForgetBase(a))
        }

        @Test
        fun `Drive delete is recycled locally with mirror deletions`() {
            val l = local("a.txt", "v1")
            assertActions(planReady(listOf(l), emptyList(), base, mirror), RecycleLocal(a, l))
        }

        @Test
        fun `mirror deletions never recycles a file edited on the phone`() {
            val l = local("a.txt", "v2", lastModified = EDITED_MTIME)
            assertActions(planReady(listOf(l), emptyList(), base, mirror), ForgetBase(a))
        }

        @Test
        fun `phone edit is kept as a local conflict copy and the Drive version wins the name`() {
            val l = local("a.txt", "phone edit", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "v1")
            val plan = planReady(listOf(l), listOf(r), base, down)
            assertActions(plan, MoveLocal(copy, a, l, MoveReason.CONFLICT_COPY), Download(a, r, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.DESTINATION_MODIFIED, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `edited on both sides keeps the phone version as a copy`() {
            val l = local("a.txt", "phone edit", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "drive edit", version = 2)
            val plan = planReady(listOf(l), listOf(r), base, down)
            assertActions(plan, MoveLocal(copy, a, l, MoveReason.CONFLICT_COPY), Download(a, r, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `new on both sides with different content`() {
            val l = local("a.txt", "phone")
            val r = remote("a.txt", "drive")
            val plan = planReady(listOf(l), listOf(r), null, down)
            assertActions(plan, MoveLocal(copy, a, l, MoveReason.CONFLICT_COPY), Download(a, r, replaces = null))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_NEW, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `ask policy leaves a diverged destination pending`() {
            val l = local("a.txt", "phone edit", lastModified = EDITED_MTIME)
            val ask = PairSettings(direction = SyncDirection.DOWNLOAD_ONLY, conflictPolicy = ConflictPolicy.ASK)
            val plan = planReady(listOf(l), listOf(remote("a.txt", "v1")), base, ask)
            assertActions(plan)
            assertConflicts(plan, Conflict(a, ConflictKind.DESTINATION_MODIFIED, ConflictResolution.PENDING))
        }
    }

    @ParameterizedTest
    @EnumSource(value = ConflictPolicy::class, names = ["ASK"], mode = EnumSource.Mode.EXCLUDE)
    fun `one-way divergence ignores every policy except ask`(policy: ConflictPolicy) {
        val l = local("a.txt", "phone edit", lastModified = 9_999)
        val r = remote("a.txt", "drive edit", version = 2, modifiedTime = 1)
        val upload = PairSettings(direction = SyncDirection.UPLOAD_ONLY, conflictPolicy = policy)
        val download = PairSettings(direction = SyncDirection.DOWNLOAD_ONLY, conflictPolicy = policy)
        assertActions(
            planReady(listOf(l), listOf(r), base, upload),
            MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY),
            Upload(a, l, replaces = null),
        )
        assertActions(
            planReady(listOf(l), listOf(r), base, download),
            MoveLocal(copy, a, l, MoveReason.CONFLICT_COPY),
            Download(a, r, replaces = null),
        )
    }
}
