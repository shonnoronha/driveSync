package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.plan.SyncAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Action order: folder creates, moves, uploads, downloads, base bookkeeping, deletes deepest first. */
class SyncPlannerOrderTest {
    @Test
    fun `every phase appears in order`() {
        val touched = local("touch.txt", "t", lastModified = EDITED_MTIME)
        val deletedOnDrive = synced("d/x.txt", "x")
        val deletedOnPhone = synced("t.txt", "t")
        val plan =
            planReady(
                listOf(
                    localFolder("n"),
                    localFolder("d"),
                    local("moved.txt", "mv"),
                    local("up.txt", "new", lastModified = EDITED_MTIME),
                    local("down.txt", "old"),
                    touched,
                    deletedOnDrive.local,
                ),
                listOf(
                    remoteFolder("m"),
                    remote("old.txt", "mv"),
                    remote("up.txt", "old"),
                    remote("down.txt", "new", version = 2),
                    remote("touch.txt", "t"),
                    deletedOnPhone.remote,
                ),
                listOf(
                    baseFolder("d"),
                    base("old.txt", "mv"),
                    base("up.txt", "old"),
                    base("down.txt", "old"),
                    base("gone.txt", "g"),
                    base("touch.txt", "t"),
                    deletedOnDrive.base,
                    deletedOnPhone.base,
                ),
            )
        assertEquals(
            listOf(
                "CreateLocalFolder:m",
                "CreateRemoteFolder:n",
                "MoveRemote:moved.txt",
                "Upload:up.txt",
                "Download:down.txt",
                "ForgetBase:gone.txt",
                "Link:touch.txt",
                "RecycleLocal:d/x.txt",
                "RecycleLocal:d",
                "TrashRemote:t.txt",
            ),
            plan.summary(),
        )
    }

    @Test
    fun `deletes are deepest first across both sides`() {
        val deep = synced("a/b/c/deep.txt", "1")
        val mid = synced("a/b/mid.txt", "2")
        val top = synced("top.txt", "3")
        val plan =
            planReady(
                listOf(mid.local),
                listOf(deep.remote, top.remote),
                listOf(deep.base, mid.base, top.base),
            )
        assertEquals(
            listOf("TrashRemote:a/b/c/deep.txt", "RecycleLocal:a/b/mid.txt", "TrashRemote:top.txt"),
            plan.summary(),
        )
    }

    @Test
    fun `within a phase actions are sorted by path`() {
        val files = listOf("c.txt", "a.txt", "b/z.txt", "b.txt").map { local(it, it) }
        val plan = planReady(files + localFolder("b"), emptyList(), emptyList())
        assertEquals(
            listOf("CreateRemoteFolder:b", "Upload:a.txt", "Upload:b.txt", "Upload:b/z.txt", "Upload:c.txt"),
            plan.summary(),
        )
    }

    @Test
    fun `deleteCount counts file deletes only`() {
        val x = synced("d/x.txt", "x")
        val plan = planReady(listOf(localFolder("d"), x.local), emptyList(), listOf(baseFolder("d"), x.base))
        assertEquals(2, plan.actions.count { it is SyncAction.RecycleLocal })
        assertEquals(1, plan.deleteCount)
    }

    @Test
    fun `the planner is deterministic regardless of input order`() {
        val local = listOf(local("a.txt", "1"), local("b.txt", "2"), localFolder("d"), local("d/c.txt", "3"))
        val remote = listOf(remote("e.txt", "4"), remoteFolder("f"), remote("a.txt", "x"))
        val first = planReady(local, remote, null)
        val second = planReady(local.reversed(), remote.reversed(), null)
        assertEquals(first, second)
    }
}
