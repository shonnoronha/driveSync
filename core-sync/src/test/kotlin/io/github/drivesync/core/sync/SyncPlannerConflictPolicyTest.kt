package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import io.github.drivesync.core.sync.plan.SyncPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** PRD 3.5 conflict policies in a TWO_WAY pair. */
class SyncPlannerConflictPolicyTest {
    private val a = path("a.txt")
    private val copy = conflictCopy("a.txt")

    private fun policy(p: ConflictPolicy) = PairSettings(conflictPolicy = p)

    private fun bothModified(
        p: ConflictPolicy,
        l: LocalEntry = local("a.txt", "phone", lastModified = 5_000),
        r: RemoteEntry = remote("a.txt", "drive", version = 2, modifiedTime = 3_000),
    ): SyncPlan = planReady(listOf(l), listOf(r), listOf(base("a.txt", "v1")), policy(p))

    private fun assertKeptBoth(
        plan: SyncPlan,
        l: LocalEntry,
        r: RemoteEntry,
        kind: ConflictKind,
    ) {
        assertActions(
            plan,
            MoveRemote(copy, a, r, MoveReason.CONFLICT_COPY),
            Upload(a, l, replaces = null),
            Download(copy, r.copy(path = copy), replaces = null),
        )
        assertConflicts(plan, Conflict(a, kind, ConflictResolution.KEPT_BOTH, copy))
    }

    @Nested
    inner class BothModified {
        private val l = local("a.txt", "phone", lastModified = 5_000)
        private val r = remote("a.txt", "drive", version = 2, modifiedTime = 3_000)

        @Test
        fun `keep both is the default`() {
            assertEquals(ConflictPolicy.KEEP_BOTH, PairSettings().conflictPolicy)
            assertKeptBoth(bothModified(ConflictPolicy.KEEP_BOTH, l, r), l, r, ConflictKind.BOTH_MODIFIED)
        }

        @Test
        fun `local wins overwrites Drive`() {
            val plan = bothModified(ConflictPolicy.LOCAL_WINS, l, r)
            assertActions(plan, Upload(a, l, replaces = r))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.LOCAL_WON))
        }

        @Test
        fun `remote wins overwrites the phone`() {
            val plan = bothModified(ConflictPolicy.REMOTE_WINS, l, r)
            assertActions(plan, Download(a, r, replaces = l))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.REMOTE_WON))
        }

        @Test
        fun `newest wins - newer phone file uploads`() {
            val plan = bothModified(ConflictPolicy.NEWEST_WINS, l, r)
            assertActions(plan, Upload(a, l, replaces = r))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.LOCAL_WON))
        }

        @Test
        fun `newest wins - newer Drive file downloads`() {
            val newer = r.copy(modifiedTime = 9_000)
            val plan = bothModified(ConflictPolicy.NEWEST_WINS, l, newer)
            assertActions(plan, Download(a, newer, replaces = l))
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.REMOTE_WON))
        }

        @Test
        fun `newest wins - a tie keeps both`() {
            val tie = r.copy(modifiedTime = l.lastModified)
            assertKeptBoth(bothModified(ConflictPolicy.NEWEST_WINS, l, tie), l, tie, ConflictKind.BOTH_MODIFIED)
        }

        @Test
        fun `ask leaves the conflict pending with no actions`() {
            val plan = bothModified(ConflictPolicy.ASK, l, r)
            assertActions(plan)
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_MODIFIED, ConflictResolution.PENDING))
        }

        @ParameterizedTest
        @EnumSource(ConflictPolicy::class)
        fun `same content is never a conflict`(p: ConflictPolicy) {
            val same = local("a.txt", "drive", lastModified = 5_000)
            val plan = bothModified(p, same, r)
            assertActions(plan, Link(a, same, r))
            assertConflicts(plan)
        }
    }

    @Nested
    inner class BothNew {
        private val l = local("a.txt", "phone", lastModified = 5_000)
        private val r = remote("a.txt", "drive", modifiedTime = 3_000)

        private fun plan(p: ConflictPolicy) = planReady(listOf(l), listOf(r), null, policy(p))

        @Test
        fun `keep both`() {
            assertKeptBoth(plan(ConflictPolicy.KEEP_BOTH), l, r, ConflictKind.BOTH_NEW)
        }

        @Test
        fun `local wins`() {
            assertActions(plan(ConflictPolicy.LOCAL_WINS), Upload(a, l, replaces = r))
            assertEquals(ConflictKind.BOTH_NEW, plan(ConflictPolicy.LOCAL_WINS).conflicts.single().kind)
        }

        @Test
        fun `remote wins`() {
            assertActions(plan(ConflictPolicy.REMOTE_WINS), Download(a, r, replaces = l))
        }

        @Test
        fun `newest wins`() {
            assertActions(plan(ConflictPolicy.NEWEST_WINS), Upload(a, l, replaces = r))
        }

        @Test
        fun `ask`() {
            val plan = plan(ConflictPolicy.ASK)
            assertActions(plan)
            assertConflicts(plan, Conflict(a, ConflictKind.BOTH_NEW, ConflictResolution.PENDING))
        }
    }

    @Nested
    inner class ModifyDelete {
        private val base = listOf(base("a.txt", "v1"))

        @ParameterizedTest
        @EnumSource(value = ConflictPolicy::class, names = ["ASK"], mode = EnumSource.Mode.EXCLUDE)
        fun `local edit beats a Drive delete under every policy`(p: ConflictPolicy) {
            val l = local("a.txt", "phone", lastModified = 1)
            val plan = planReady(listOf(l), emptyList(), base, policy(p))
            assertActions(plan, Upload(a, l, replaces = null))
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @ParameterizedTest
        @EnumSource(value = ConflictPolicy::class, names = ["ASK"], mode = EnumSource.Mode.EXCLUDE)
        fun `Drive edit beats a local delete under every policy`(p: ConflictPolicy) {
            val r = remote("a.txt", "drive", version = 2, modifiedTime = 1)
            val plan = planReady(emptyList(), listOf(r), base, policy(p))
            assertActions(plan, Download(a, r, replaces = null))
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, ConflictResolution.KEPT_MODIFIED),
            )
        }

        @Test
        fun `ask leaves both modify-delete kinds pending`() {
            val l = local("a.txt", "phone", lastModified = EDITED_MTIME)
            val r = remote("b.txt", "drive", version = 2)
            val plan =
                planReady(
                    listOf(l),
                    listOf(r),
                    listOf(base("a.txt", "v1"), base("b.txt", "v1")),
                    policy(ConflictPolicy.ASK),
                )
            assertActions(plan)
            assertConflicts(
                plan,
                Conflict(a, ConflictKind.LOCAL_MODIFIED_REMOTE_DELETED, ConflictResolution.PENDING),
                Conflict(path("b.txt"), ConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, ConflictResolution.PENDING),
            )
        }
    }

    @Nested
    inner class ConflictCopyNames {
        @Test
        fun `a taken conflict name gets a counter`() {
            val l = local("a.txt", "phone", lastModified = EDITED_MTIME)
            val r = remote("a.txt", "drive", version = 2)
            val existing = synced(copy.value, "old copy")
            val plan =
                planReady(
                    listOf(l, existing.local),
                    listOf(r, existing.remote),
                    listOf(base("a.txt", "v1"), existing.base),
                )
            val second = conflictCopy("a.txt", 2)
            assertActions(
                plan,
                MoveRemote(second, a, r, MoveReason.CONFLICT_COPY),
                Upload(a, l, replaces = null),
                Download(second, r.copy(path = second), replaces = null),
            )
        }

        @Test
        fun `a name used only on one side is still avoided`() {
            val l = local("a.txt", "phone")
            val r = remote("a.txt", "drive")
            val plan = planReady(listOf(l), listOf(r, remote(copy.value, "drive-only")), null)
            assertEquals(conflictCopy("a.txt", 2), plan.conflicts.single().conflictCopy)
        }

        @Test
        fun `conflicts in a subfolder stay in that subfolder`() {
            val l = local("d/a.txt", "phone")
            val r = remote("d/a.txt", "drive")
            val plan = planReady(listOf(localFolder("d"), l), listOf(remoteFolder("d"), r), null)
            val sub = conflictCopy("d/a.txt")
            assertEquals(path("d/a ($STAMP_LABEL).txt"), sub)
            assertEquals(sub, plan.conflicts.single().conflictCopy)
        }

        @Test
        fun `two conflicting files get distinct copies`() {
            val plan =
                planReady(
                    listOf(local("a.txt", "p1"), local("a.md", "p2")),
                    listOf(remote("a.txt", "d1"), remote("a.md", "d2")),
                    null,
                )
            assertEquals(
                listOf(conflictCopy("a.md"), conflictCopy("a.txt")),
                plan.conflicts.map { it.conflictCopy },
            )
        }
    }
}
