package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.MassDeleteGuard
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.PauseReason
import io.github.drivesync.core.sync.plan.PlanInput
import io.github.drivesync.core.sync.plan.PlanResult
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.plan.SyncAction.CreateLocalFolder
import io.github.drivesync.core.sync.plan.SyncAction.CreateRemoteFolder
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.Link
import io.github.drivesync.core.sync.plan.SyncAction.RecycleLocal
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import io.github.drivesync.core.sync.plan.SyncPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** PRD 3.9 rules owned by the planner: 1, 3, 6, 7, 8, 9, 10. */
class SyncPlannerSafetyTest {
    private val f = synced("a.txt", "v1")
    private val uploadOnly = settings(SyncDirection.UPLOAD_ONLY)

    private fun paused(result: PlanResult): PauseReason = assertInstanceOf(PlanResult.Paused::class.java, result).reason

    private fun input(
        local: Snapshot<LocalEntry>,
        remote: Snapshot<RemoteEntry>,
        base: BaseSnapshot? = BaseSnapshot(listOf(f.base)),
    ) = PlanInput(local, remote, base, PairSettings(), testStamp)

    @Nested
    inner class Rule1Unreachable {
        @Test
        fun `unreachable local folder pauses the pair`() {
            val result =
                SyncPlanner.plan(input(Snapshot.Unavailable("SD card removed"), Snapshot.Available(listOf(f.remote))))
            assertEquals(PauseReason.LocalUnreachable("SD card removed"), paused(result))
        }

        @Test
        fun `unreachable Drive folder pauses the pair`() {
            val result = SyncPlanner.plan(input(Snapshot.Available(listOf(f.local)), Snapshot.Unavailable("trashed")))
            assertEquals(PauseReason.RemoteUnreachable("trashed"), paused(result))
        }

        @Test
        fun `unreachable side pauses even without a base`() {
            val result = SyncPlanner.plan(input(Snapshot.Unavailable("x"), Snapshot.Available(emptyList()), null))
            assertEquals(PauseReason.LocalUnreachable("x"), paused(result))
        }

        @Test
        fun `both unreachable reports the local side first`() {
            val result = SyncPlanner.plan(input(Snapshot.Unavailable("l"), Snapshot.Unavailable("r")))
            assertEquals(PauseReason.LocalUnreachable("l"), paused(result))
        }
    }

    @Nested
    inner class Rule1SuddenlyEmpty {
        @Test
        fun `local side with no files while the base has files pauses`() {
            assertEquals(PauseReason.LocalSuddenlyEmpty, paused(planOf(emptyList(), listOf(f.remote), listOf(f.base))))
        }

        @Test
        fun `local side with only folders counts as empty`() {
            val result = planOf(listOf(localFolder("d")), listOf(f.remote, remoteFolder("d")), listOf(f.base))
            assertEquals(PauseReason.LocalSuddenlyEmpty, paused(result))
        }

        @Test
        fun `local side with only ignored files counts as empty`() {
            val result = planOf(listOf(local("x.tmp", "t")), listOf(f.remote), listOf(f.base))
            assertEquals(PauseReason.LocalSuddenlyEmpty, paused(result))
        }

        @Test
        fun `Drive side with no files while the base has files pauses`() {
            assertEquals(PauseReason.RemoteSuddenlyEmpty, paused(planOf(listOf(f.local), emptyList(), listOf(f.base))))
        }

        @Test
        fun `Drive side with only Google-native files counts as empty`() {
            val result = planOf(listOf(f.local), listOf(googleNative("Doc")), listOf(f.base))
            assertEquals(PauseReason.RemoteSuddenlyEmpty, paused(result))
        }

        @Test
        fun `deleting the last file of a one-file pair pauses instead of trashing`() {
            assertEquals(PauseReason.LocalSuddenlyEmpty, paused(planOf(emptyList(), listOf(f.remote), listOf(f.base))))
        }

        @Test
        fun `both sides empty with a base of files pauses on the local side`() {
            assertEquals(PauseReason.LocalSuddenlyEmpty, paused(planOf(emptyList(), emptyList(), listOf(f.base))))
        }

        @Test
        fun `a base of folders only never pauses`() {
            val plan = planOf(emptyList(), emptyList(), listOf(baseFolder("d"))).ready()
            assertActions(plan, SyncAction.ForgetBase(path("d")))
        }

        @Test
        fun `empty sides with an empty base are fine`() {
            assertNothingToDo(planOf(emptyList(), emptyList(), emptyList()).ready())
        }

        @Test
        fun `first sync into an empty local folder is not suddenly empty`() {
            val plan = planOf(emptyList(), listOf(f.remote), null).ready()
            assertActions(plan, Download(f.remote.path, f.remote, replaces = null))
        }

        @ParameterizedTest
        @EnumSource(SyncDirection::class)
        fun `applies to every direction`(direction: SyncDirection) {
            val mirror = settings(direction, mirrorDeletions = true)
            val result = planOf(listOf(f.local), emptyList(), listOf(f.base), mirror)
            assertEquals(PauseReason.RemoteSuddenlyEmpty, paused(result))
        }
    }

    @Nested
    inner class Rule3NoCrossSideClocks {
        @Test
        fun `Drive modifiedTime far in the future with unchanged md5 does nothing`() {
            val r = remote("a.txt", "v1", modifiedTime = Long.MAX_VALUE)
            assertNothingToDo(planReady(listOf(f.local), listOf(r), listOf(f.base)))
        }

        @Test
        fun `Drive modifiedTime far in the past with unchanged md5 does nothing`() {
            val r = remote("a.txt", "v1", modifiedTime = 0)
            assertNothingToDo(planReady(listOf(f.local), listOf(r), listOf(f.base)))
        }

        @Test
        fun `local clock jumping back with unchanged content only refreshes the base`() {
            val l = local("a.txt", "v1", lastModified = 1)
            assertActions(planReady(listOf(l), listOf(f.remote), listOf(f.base)), Link(l.path, l, f.remote))
        }

        @Test
        fun `a newer local mtime does not beat a real Drive edit`() {
            val l = local("a.txt", "v1")
            val r = remote("a.txt", "v2", version = 2, modifiedTime = 0)
            val plan = planReady(listOf(l), listOf(r), listOf(f.base))
            assertActions(plan, Download(r.path, r, replaces = l))
        }

        @Test
        fun `an older local edit still uploads over a Drive file with a future timestamp`() {
            val l = local("a.txt", "v2", lastModified = 10)
            val r = remote("a.txt", "v1", modifiedTime = Long.MAX_VALUE)
            assertActions(planReady(listOf(l), listOf(r), listOf(f.base)), Upload(l.path, l, replaces = r))
        }

        @Test
        fun `both edited - default policy keeps both whatever the timestamps say`() {
            val l = local("a.txt", "mine", lastModified = Long.MAX_VALUE)
            val r = remote("a.txt", "theirs", version = 2, modifiedTime = 0)
            val plan = planReady(listOf(l), listOf(r), listOf(f.base))
            assertEquals(ConflictResolution.KEPT_BOTH, plan.conflicts.single().resolution)
        }
    }

    @Nested
    inner class Rule6IgnoredFiles {
        @Test
        fun `temp and partial files are invisible on both sides`() {
            val local =
                listOf(
                    local(".a.txt.drivesync-part", "half"),
                    local("b.crdownload", "half"),
                    local("~\$lock.docx", "lock"),
                    local(".thumbnails/t.jpg", "thumb"),
                    local(".trashed-123-old.jpg", "old"),
                    local(".drivesync-recycle/a.txt", "recycled"),
                )
            val remote = listOf(remote("c.tmp", "x"), remote("d.part", "y"), remote(".thumbnails/u.jpg", "z"))
            assertNothingToDo(planReady(local, remote, emptyList()))
        }

        @Test
        fun `an ignored temp file next to a real one does not disturb the real file`() {
            val temp = local(".a.txt.drivesync-part", "half")
            assertNothingToDo(planReady(listOf(f.local, temp), listOf(f.remote), listOf(f.base)))
        }

        @Test
        fun `an ignored folder is not created on the other side`() {
            assertNothingToDo(planReady(listOf(localFolder(".thumbnails")), emptyList(), null))
        }
    }

    @Nested
    inner class Rule7NoBase {
        @Test
        fun `merges both sides without any delete`() {
            val onlyLocal = local("l.txt", "L")
            val onlyRemote = remote("r.txt", "R")
            val same = synced("same.txt", "S")
            val plan =
                planOf(
                    listOf(onlyLocal, same.local, localFolder("ld")),
                    listOf(onlyRemote, same.remote, remoteFolder("rd")),
                    null,
                ).ready()
            assertActions(
                plan,
                CreateRemoteFolder(path("ld")),
                CreateLocalFolder(path("rd")),
                Upload(onlyLocal.path, onlyLocal, replaces = null),
                Download(onlyRemote.path, onlyRemote, replaces = null),
                Link(same.local.path, same.local, same.remote),
            )
            assertConflicts(plan)
        }

        @ParameterizedTest
        @EnumSource(SyncDirection::class)
        fun `never deletes, even with mirror deletions`(direction: SyncDirection) {
            val plan =
                planOf(
                    listOf(local("l.txt", "L"), local("both.txt", "mine")),
                    listOf(remote("r.txt", "R"), remote("both.txt", "theirs")),
                    null,
                    settings(direction, mirrorDeletions = true),
                ).ready()
            assertTrue(plan.actions.none { it is TrashRemote || it is RecycleLocal }, plan.summary().toString())
        }

        @ParameterizedTest
        @EnumSource(ConflictPolicy::class)
        fun `never deletes under any conflict policy`(policy: ConflictPolicy) {
            val plan =
                planOf(
                    listOf(local("both.txt", "mine", lastModified = 5)),
                    listOf(remote("both.txt", "theirs", modifiedTime = 9)),
                    null,
                    PairSettings(conflictPolicy = policy),
                ).ready()
            assertTrue(plan.actions.none { it is TrashRemote || it is RecycleLocal }, plan.summary().toString())
        }

        @Test
        fun `an empty base snapshot behaves like a first sync`() {
            val l = local("l.txt", "L")
            assertActions(planOf(listOf(l), emptyList(), emptyList()).ready(), Upload(l.path, l, replaces = null))
        }
    }

    @Nested
    inner class Rule8Direction {
        private val g = synced("b.txt", "v1")

        @Test
        fun `upload-only keeps the Drive copy of a local delete`() {
            val plan = planReady(listOf(g.local), listOf(f.remote, g.remote), listOf(f.base, g.base), uploadOnly)
            assertActions(plan, SyncAction.ForgetBase(f.base.path))
        }

        @Test
        fun `upload-only with mirror deletions trashes on Drive`() {
            val mirror = settings(SyncDirection.UPLOAD_ONLY, mirrorDeletions = true)
            val plan = planReady(listOf(g.local), listOf(f.remote, g.remote), listOf(f.base, g.base), mirror)
            assertActions(plan, TrashRemote(f.remote.path, f.remote))
        }

        @Test
        fun `download-only keeps the local copy of a Drive delete`() {
            val downloadOnly = settings(SyncDirection.DOWNLOAD_ONLY)
            val plan = planReady(listOf(f.local, g.local), listOf(g.remote), listOf(f.base, g.base), downloadOnly)
            assertActions(plan, SyncAction.ForgetBase(f.base.path))
        }

        @Test
        fun `download-only with mirror deletions recycles locally`() {
            val mirror = settings(SyncDirection.DOWNLOAD_ONLY, mirrorDeletions = true)
            val plan = planReady(listOf(f.local, g.local), listOf(g.remote), listOf(f.base, g.base), mirror)
            assertActions(plan, RecycleLocal(f.local.path, f.local))
        }

        @Test
        fun `two-way propagates deletes regardless of the mirror flag`() {
            val plan = planReady(listOf(g.local), listOf(f.remote, g.remote), listOf(f.base, g.base))
            assertActions(plan, TrashRemote(f.remote.path, f.remote))
        }
    }

    @Nested
    inner class Rule9MassDeleteGuard {
        private fun names(n: Int) = (0 until n).map { "f%03d.bin".format(it) }

        /** A pair of [total] synced files where the first [deletedLocally] were deleted on the phone. */
        private fun localDeletes(
            total: Int,
            deletedLocally: Int,
            settings: PairSettings = PairSettings(),
            confirmed: Boolean = false,
        ): PlanResult {
            val files = names(total).map { synced(it, it) }
            return planOf(
                files.drop(deletedLocally).map { it.local },
                files.map { it.remote },
                files.map { it.base },
                settings,
                confirmed,
            )
        }

        @Test
        fun `more than 20 files and more than 10 percent asks first`() {
            val result = assertInstanceOf(PlanResult.NeedsDeleteConfirmation::class.java, localDeletes(100, 21))
            assertEquals(21, result.deleteCount)
            assertEquals(100, result.pairFileCount)
            assertEquals(21, result.plan.actions.count { it is TrashRemote })
            assertEquals(21, result.plan.deleteCount)
        }

        @Test
        fun `exactly 20 deletes does not trip the guard`() {
            assertEquals(20, localDeletes(100, 20).ready().deleteCount)
        }

        @Test
        fun `confirmed deletions run`() {
            assertEquals(21, localDeletes(100, 21, confirmed = true).ready().deleteCount)
        }

        @Test
        fun `exactly 10 percent does not trip the guard`() {
            assertEquals(21, localDeletes(210, 21).ready().deleteCount)
        }

        @Test
        fun `just over 10 percent trips the guard`() {
            assertInstanceOf(PlanResult.NeedsDeleteConfirmation::class.java, localDeletes(209, 21))
        }

        @Test
        fun `many deletes in a huge pair are fine`() {
            assertEquals(50, localDeletes(1_000, 50).ready().deleteCount)
        }

        @Test
        fun `deletes on both sides add up`() {
            val files = names(100).map { synced(it, it) }
            val result =
                planOf(
                    files.drop(11).map { it.local },
                    files.take(11).map { it.remote } + files.drop(22).map { it.remote },
                    files.map { it.base },
                )
            val confirm = assertInstanceOf(PlanResult.NeedsDeleteConfirmation::class.java, result)
            assertEquals(22, confirm.deleteCount)
            assertEquals(11, confirm.plan.actions.count { it is RecycleLocal })
        }

        @Test
        fun `folder deletes are not counted and base folders are not pair files`() {
            val files = names(10).map { synced(it, it) }
            val folders = (0 until 30).map { "dir$it" }
            val result =
                planOf(
                    files.map { it.local },
                    files.map { it.remote } + folders.map { remoteFolder(it) },
                    files.map { it.base } + folders.map { baseFolder(it) },
                    PairSettings(massDeleteGuard = MassDeleteGuard(minFiles = 0, minFraction = 0.0)),
                )
            val plan = result.ready()
            assertEquals(30, plan.actions.count { it is TrashRemote })
            assertEquals(0, plan.deleteCount)
        }

        @Test
        fun `custom thresholds are honoured`() {
            val strict = PairSettings(massDeleteGuard = MassDeleteGuard(minFiles = 2, minFraction = 0.0))
            val result = assertInstanceOf(PlanResult.NeedsDeleteConfirmation::class.java, localDeletes(100, 3, strict))
            assertEquals(3, result.deleteCount)
            assertEquals(2, localDeletes(100, 2, strict).ready().deleteCount)
        }

        @Test
        fun `kept modify-delete conflicts are not deletes`() {
            val files = names(100).map { synced(it, it) }
            val edited = files.take(30).map { local(it.local.path.value, "edited", lastModified = EDITED_MTIME) }
            val kept = files.drop(30)
            val result = planOf(edited + kept.map { it.local }, kept.map { it.remote }, files.map { it.base })
            assertEquals(0, result.ready().deleteCount)
        }
    }

    @Nested
    inner class Rule10TrashNotDelete {
        @Test
        fun `local delete becomes Drive trash and Drive delete becomes local recycle`() {
            val g = synced("b.txt", "v1")
            val plan = planReady(listOf(g.local), listOf(f.remote), listOf(f.base, g.base))
            assertActions(plan, TrashRemote(f.remote.path, f.remote), RecycleLocal(g.local.path, g.local))
            assertEquals(2, plan.deleteCount)
        }
    }
}
