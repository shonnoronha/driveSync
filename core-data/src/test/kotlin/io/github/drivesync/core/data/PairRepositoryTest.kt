package io.github.drivesync.core.data

import io.github.drivesync.core.data.db.PairStatus
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.MassDeleteGuard
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.store.BaseUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PairRepositoryTest : DbTest() {
    private var ids = 0
    private val repo = PairRepository(db, { now }, { "pair-${++ids}" })

    private suspend fun addPair(name: String = "Notes") =
        repo.add(repo.newPairId(), name, "content://tree/$name", "drive-$name")

    private fun entry(
        path: String,
        md5: String = "m",
    ) = BaseEntry(
        RelPath.of(path),
        isFolder = false,
        md5 = md5,
        localSize = 1,
        localLastModified = 2,
        remoteFileId = "id",
        remoteVersion = 3,
    )

    @Test
    fun `adds pairs with default settings`() =
        runTest {
            val pair = addPair()
            assertEquals("pair-1", pair.id)
            assertEquals(PairSettings(), pair.settings)
            assertEquals(PairStatus.IDLE, pair.status)
            assertEquals(listOf(pair), repo.observePairs().first())
            assertEquals(pair, repo.pair("pair-1"))
        }

    @Test
    fun `pairs are listed in creation order`() =
        runTest {
            addPair("B")
            now++
            addPair("A")
            assertEquals(listOf("B", "A"), repo.pairs().map { it.name })
        }

    @Test
    fun `updates settings and pauses`() =
        runTest {
            val pair = addPair()
            val custom = PairSettings(SyncDirection.UPLOAD_ONLY, ConflictPolicy.ASK, true, MassDeleteGuard(5, 0.5))
            repo.updateSettings(pair.id, custom)
            repo.setEnabled(pair.id, false)
            val updated = repo.pair(pair.id)!!
            assertEquals(custom, updated.settings)
            assertFalse(updated.enabled)
            repo.updateSettings("missing", custom) // No-op.
        }

    @Test
    fun `a new pair has no base`() =
        runTest {
            assertNull(repo.baseStore(addPair().id).load())
        }

    @Test
    fun `an empty commit creates an empty base`() =
        runTest {
            val base = repo.baseStore(addPair().id)
            base.commit(emptyList())
            assertEquals(emptyList<BaseEntry>(), base.load()!!.entries)
        }

    @Test
    fun `commits puts and removes`() =
        runTest {
            val base = repo.baseStore(addPair().id)
            base.commit(listOf(BaseUpdate.Put(entry("a")), BaseUpdate.Put(entry("b"))))
            base.commit(listOf(BaseUpdate.Remove(RelPath.of("a")), BaseUpdate.Put(entry("b", "new"))))
            assertEquals(listOf(entry("b", "new")), base.load()!!.entries)
        }

    @Test
    fun `later updates in one commit win`() =
        runTest {
            val base = repo.baseStore(addPair().id)
            base.commit(
                listOf(BaseUpdate.Put(entry("a")), BaseUpdate.Remove(RelPath.of("a")), BaseUpdate.Put(entry("c"))),
            )
            base.commit(listOf(BaseUpdate.Remove(RelPath.of("c")), BaseUpdate.Put(entry("c", "again"))))
            assertEquals(listOf(entry("c", "again")), base.load()!!.entries)
        }

    @Test
    fun `bases are kept per pair`() =
        runTest {
            val one = repo.baseStore(addPair("1").id)
            val two = repo.baseStore(addPair("2").id)
            one.commit(listOf(BaseUpdate.Put(entry("a"))))
            assertEquals(1, one.load()!!.entries.size)
            assertNull(two.load())
        }

    @Test
    fun `relink forgets the base`() =
        runTest {
            val pair = addPair()
            repo.baseStore(pair.id).commit(listOf(BaseUpdate.Put(entry("a"))))
            repo.relink(pair.id, "new-folder")
            assertEquals("new-folder", repo.pair(pair.id)!!.driveFolderId)
            assertNull(repo.baseStore(pair.id).load())
            repo.relink("missing", "x") // No-op.
        }

    @Test
    fun `remove deletes the pair and its base`() =
        runTest {
            val pair = addPair()
            repo.baseStore(pair.id).commit(listOf(BaseUpdate.Put(entry("a"))))
            repo.remove(pair.id)
            assertNull(repo.pair(pair.id))
            assertEquals(emptyList<Any>(), db.base().entries(pair.id))
        }

    @Test
    fun `the default constructor makes ids`() {
        val repo = PairRepository(db)
        assertEquals(36, repo.newPairId().length)
    }
}
