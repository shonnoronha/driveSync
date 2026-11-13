package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The user's per-file answers to pending conflicts override the pair's policy for those files only. */
class SyncPlannerResolutionTest {
    private val ask = PairSettings(conflictPolicy = ConflictPolicy.ASK)

    @Test
    fun `an answer resolves its file and leaves the others pending`() {
        val la = local("a.txt", "phone a", lastModified = 5_000)
        val ra = remote("a.txt", "drive a", version = 2)
        val lb = local("b.txt", "phone b", lastModified = 5_000)
        val rb = remote("b.txt", "drive b", version = 2)
        val plan =
            planReady(
                listOf(la, lb),
                listOf(ra, rb),
                listOf(base("a.txt", "v1"), base("b.txt", "v1")),
                ask,
                resolutions = mapOf(path("a.txt") to ConflictPolicy.LOCAL_WINS),
            )
        assertActions(plan, Upload(path("a.txt"), la, replaces = ra))
        assertEquals(
            mapOf(path("a.txt") to ConflictResolution.LOCAL_WON, path("b.txt") to ConflictResolution.PENDING),
            plan.conflicts.associate { it.path to it.resolution },
        )
    }

    @Test
    fun `keep the Drive version`() {
        val l = local("a.txt", "phone", lastModified = 5_000)
        val r = remote("a.txt", "drive", version = 2)
        val plan =
            planReady(
                listOf(l),
                listOf(r),
                listOf(base("a.txt", "v1")),
                ask,
                mapOf(path("a.txt") to ConflictPolicy.REMOTE_WINS),
            )
        assertActions(plan, Download(path("a.txt"), r, replaces = l))
    }

    @Test
    fun `modify-delete answered keeps the modified file`() {
        val l = local("a.txt", "edited", lastModified = 5_000)
        val plan =
            planReady(
                listOf(l),
                emptyList(),
                listOf(base("a.txt", "v1")),
                ask,
                mapOf(path("a.txt") to ConflictPolicy.KEEP_BOTH),
            )
        assertEquals(ConflictResolution.KEPT_MODIFIED, plan.conflicts.single().resolution)
    }

    @Test
    fun `one-way divergence answered keeps both on the destination`() {
        val l = local("a.txt", "phone", lastModified = 5_000)
        val r = remote("a.txt", "drive", version = 2)
        val plan =
            planReady(
                listOf(l),
                listOf(r),
                listOf(base("a.txt", "v1")),
                ask.copy(direction = SyncDirection.UPLOAD_ONLY),
                mapOf(path("a.txt") to ConflictPolicy.KEEP_BOTH),
            )
        assertEquals(ConflictResolution.KEPT_BOTH, plan.conflicts.single().resolution)
        assertTrue(plan.actions.isNotEmpty())
    }

    @Test
    fun `an ASK answer keeps the file pending`() {
        val l = local("a.txt", "phone", lastModified = 5_000)
        val r = remote("a.txt", "drive", version = 2)
        val plan =
            planReady(
                listOf(l),
                listOf(r),
                listOf(base("a.txt", "v1")),
                PairSettings(),
                mapOf(path("a.txt") to ConflictPolicy.ASK),
            )
        assertEquals(ConflictResolution.PENDING, plan.conflicts.single().resolution)
    }
}
