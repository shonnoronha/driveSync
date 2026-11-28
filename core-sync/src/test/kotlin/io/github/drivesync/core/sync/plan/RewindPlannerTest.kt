package io.github.drivesync.core.sync.plan

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.FileFilters
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.RemoteVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RewindPlannerTest {
    private fun p(path: String) = RelPath.of(path)

    private fun file(
        path: String,
        md5: String,
        id: String = "id-$path",
    ) = BaseEntry(p(path), false, md5 = md5, localSize = 1, localLastModified = 5, remoteFileId = id, remoteVersion = 1)

    private fun folder(path: String) = BaseEntry(p(path), isFolder = true, remoteFileId = "id-$path")

    private fun map(vararg entries: BaseEntry) = entries.associateBy { it.path }

    /** Both sides exactly as [base] says. */
    private fun sides(base: Map<RelPath, BaseEntry>): Pair<List<LocalEntry>, List<RemoteEntry>> =
        base.values.map { LocalEntry(it.path, it.isFolder, it.localSize, it.localLastModified) } to
            base.values.map { RemoteEntry(it.path, it.remoteFileId!!, it.isFolder, 1, it.md5, it.remoteVersion) }

    private fun plan(
        target: Map<RelPath, BaseEntry>,
        base: Map<RelPath, BaseEntry>,
        local: List<LocalEntry> = sides(base).first,
        remote: List<RemoteEntry> = sides(base).second,
        versions: Map<String, List<RemoteVersion>> = emptyMap(),
        filters: FileFilters = FileFilters(),
    ) = RewindPlanner.plan(RewindInput(target, base, local, remote, versions, filters))

    private fun v(md5: String) = RemoteVersion("rev-$md5", md5, 1, 1)

    @Test
    fun `preview names every change`() {
        val target = map(file("kept.md", "1"), file("edited.md", "1"), file("deleted.md", "1"))
        val base = map(file("kept.md", "1"), file("edited.md", "2"), file("added.md", "1"))
        val kinds = RewindPlanner.preview(target, base).associate { it.path.value to it.kind }
        assertEquals(
            mapOf(
                "added.md" to RewindChangeKind.TO_TRASH,
                "deleted.md" to RewindChangeKind.BRING_BACK,
                "edited.md" to RewindChangeKind.RESTORE,
            ),
            kinds,
        )
    }

    @Test
    fun `an empty point is refused`() {
        assertEquals(RewindRefusal.EMPTY_POINT, RewindPlanner.refusal(map(folder("a")), map(file("x.md", "1"))))
        assertNull(RewindPlanner.refusal(map(file("x.md", "1")), map(file("x.md", "2"))))
    }

    @Test
    fun `restores use the matching version`() {
        val target = map(file("a.md", "old"))
        val base = map(file("a.md", "new"))
        val versions = mapOf("id-a.md" to listOf(v("old"), v("new")))
        val action = plan(target, base, versions = versions).actions.single() as SyncAction.Restore
        assertEquals("rev-old", action.version.revisionId)
        assertEquals("id-a.md", action.replacesRemote?.fileId)
    }

    @Test
    fun `changed since sync is skipped`() {
        val target = map(file("a.md", "old"), file("b.md", "1"))
        val base = map(file("a.md", "new"))
        val (local, remote) = sides(base)
        val edited = local.map { it.copy(size = 99) }
        val result = plan(target, base, edited, remote, mapOf("id-a.md" to listOf(v("old"))))
        assertEquals(
            listOf(
                RewindSkip(p("a.md"), RewindSkipReason.CHANGED_SINCE_SYNC),
                // Never asked Drive for b.md's versions here, so it reads as gone.
                RewindSkip(p("b.md"), RewindSkipReason.VERSION_GONE),
            ),
            result.skipped,
        )
        assertEquals(emptyList<SyncAction>(), result.actions)
    }

    @Test
    fun `a lost version is skipped`() {
        val result =
            plan(
                map(file("a.md", "old")),
                map(file("a.md", "new")),
                versions =
                    mapOf("id-a.md" to listOf(v("new"))),
            )
        assertEquals(listOf(RewindSkip(p("a.md"), RewindSkipReason.VERSION_GONE)), result.skipped)
        assertEquals(emptyList<SyncAction>(), result.actions)
    }

    @Test
    fun `new folders go only when empty after`() {
        val target = map(file("keep.md", "1"))
        val base = map(file("keep.md", "1"), folder("new"), file("new/x.md", "1"))
        val actions = plan(target, base).actions
        assertEquals(
            listOf("TrashRemote new/x.md", "RecycleLocal new/x.md", "TrashRemote new", "RecycleLocal new"),
            actions.map { "${it::class.simpleName} ${it.path}" },
        )

        val (local, remote) = sides(base)
        val unsynced = local + LocalEntry(p("new/draft.md"), false, 3, 3)
        val result = plan(target, base, unsynced, remote)
        assertEquals(listOf(RewindSkip(p("new"), RewindSkipReason.FOLDER_NOT_EMPTY)), result.skipped)
    }

    @Test
    fun `brought back in order`() {
        val target = map(folder("a"), folder("a/b"), file("a/b/c.md", "1"), file("top.md", "1"))
        val base = map(file("top.md", "1"))
        val result = plan(target, base, versions = mapOf("id-a/b/c.md" to listOf(v("1"))))
        assertEquals(
            listOf(
                "CreateRemoteFolder a",
                "CreateLocalFolder a",
                "CreateRemoteFolder a/b",
                "CreateLocalFolder a/b",
                "Restore a/b/c.md",
            ),
            result.actions.map { "${it::class.simpleName} ${it.path}" },
        )
    }

    @Test
    fun `excluded and taken names are left alone`() {
        val target = map(file("a.tmp", "1"), file("b.md", "1"))
        val base = map(file("a.tmp", "2"))
        val (local, remote) = sides(base)
        val newOnDrive = remote + RemoteEntry(p("b.md"), "other", false, 1, "x", 1)
        val result = plan(target, base, local, newOnDrive, filters = FileFilters(listOf("*.tmp")))
        assertEquals(
            setOf(
                RewindSkip(p("a.tmp"), RewindSkipReason.EXCLUDED),
                RewindSkip(p("b.md"), RewindSkipReason.NAME_TAKEN),
            ),
            result.skipped.toSet(),
        )
        assertEquals(emptyList<SyncAction>(), result.actions)
    }
}
