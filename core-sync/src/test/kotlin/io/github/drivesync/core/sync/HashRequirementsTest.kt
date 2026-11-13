package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.plan.HashRequirements
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HashRequirementsTest {
    private fun need(
        local: List<LocalEntry>,
        remote: List<RemoteEntry> = emptyList(),
        base: List<BaseEntry>? = null,
    ): Set<RelPath> = HashRequirements.localPathsNeedingHash(local, remote, base?.let(::BaseSnapshot))

    @Test
    fun `unchanged metadata needs no hash`() {
        val l = local("a", "v1", hashed = false)
        assertEquals(emptySet<RelPath>(), need(listOf(l), base = listOf(base("a", "v1"))))
    }

    @Test
    fun `changed size needs a hash`() {
        val l = local("a", "longer", hashed = false)
        assertEquals(setOf(path("a")), need(listOf(l), base = listOf(base("a", "v1"))))
    }

    @Test
    fun `changed mtime needs a hash`() {
        val l = local("a", "v1", lastModified = EDITED_MTIME, hashed = false)
        assertEquals(setOf(path("a")), need(listOf(l), base = listOf(base("a", "v1"))))
    }

    @Test
    fun `an already hashed file is never requested`() {
        val l = local("a", "longer", lastModified = EDITED_MTIME)
        assertEquals(emptySet<RelPath>(), need(listOf(l), listOf(remote("a", "x")), listOf(base("a", "v1"))))
    }

    @Test
    fun `folders are never hashed`() {
        assertEquals(emptySet<RelPath>(), need(listOf(localFolder("d")), listOf(remote("d", "x")), null))
    }

    @Test
    fun `new on both sides needs a hash to link or conflict`() {
        assertEquals(setOf(path("a")), need(listOf(local("a", "x", hashed = false)), listOf(remote("a", "x"))))
    }

    @Test
    fun `new locally next to a remote folder of the same name needs no hash`() {
        assertEquals(emptySet<RelPath>(), need(listOf(local("a", "x", hashed = false)), listOf(remoteFolder("a"))))
    }

    @Test
    fun `new local-only file without a base needs no hash`() {
        assertEquals(emptySet<RelPath>(), need(listOf(local("a", "x", hashed = false))))
    }

    @Test
    fun `new file with the size of a vanished base file may be a rename`() {
        val l = local("renamed", "12345", hashed = false)
        assertEquals(setOf(path("renamed")), need(listOf(l), base = listOf(base("old", "abcde"))))
    }

    @Test
    fun `new file with a different size than every vanished base file is not hashed`() {
        val l = local("renamed", "123456", hashed = false)
        assertEquals(emptySet<RelPath>(), need(listOf(l), base = listOf(base("old", "abcde"))))
    }

    @Test
    fun `base files still present locally are not rename candidates`() {
        val stillThere = local("old", "abcde", hashed = false)
        val fresh = local("fresh", "12345", hashed = false)
        assertEquals(emptySet<RelPath>(), need(listOf(stillThere, fresh), base = listOf(base("old", "abcde"))))
    }

    @Test
    fun `vanished base folders are not rename candidates`() {
        val fresh = LocalEntry(path("fresh"), isFolder = false, size = 0)
        assertEquals(emptySet<RelPath>(), need(listOf(fresh), base = listOf(baseFolder("gone"))))
    }

    @Test
    fun `mixed input returns exactly the paths that matter`() {
        val local =
            listOf(
                local("same", "v1", hashed = false),
                local("touched", "v1", lastModified = EDITED_MTIME, hashed = false),
                local("both-new", "n", hashed = false),
                local("solo", "only-here", hashed = false),
            )
        val base = listOf(base("same", "v1"), base("touched", "v1"))
        assertEquals(setOf(path("touched"), path("both-new")), need(local, listOf(remote("both-new", "n")), base))
    }
}
