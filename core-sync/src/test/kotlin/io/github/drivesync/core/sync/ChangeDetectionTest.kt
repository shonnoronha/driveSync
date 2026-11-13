package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.plan.Change
import io.github.drivesync.core.sync.plan.localChange
import io.github.drivesync.core.sync.plan.remoteChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Each side is compared only with its own base values (PRD 3.9 rule 3, lazy hashing). */
class ChangeDetectionTest {
    @Nested
    inner class Local {
        private val b = base("a", "v1")

        @Test
        fun `absent and new without a base`() {
            assertEquals(Change.ABSENT, localChange(null, null))
            assertEquals(Change.NEW, localChange(local("a", "v1"), null))
        }

        @Test
        fun `deleted when the base exists but the file does not`() {
            assertEquals(Change.DELETED, localChange(null, b))
        }

        @Test
        fun `size and mtime equal to the base means unchanged, even with a different or missing hash`() {
            assertEquals(Change.UNCHANGED, localChange(local("a", "v1"), b))
            assertEquals(Change.UNCHANGED, localChange(local("a", "v1", hashed = false), b))
            // Same size, same mtime, different bytes: the quick check wins (PRD 3.9 lazy hashing).
            assertEquals(Change.UNCHANGED, localChange(local("a", "v9"), b))
        }

        @Test
        fun `touched file with the base hash is unchanged`() {
            assertEquals(Change.UNCHANGED, localChange(local("a", "v1", lastModified = EDITED_MTIME), b))
        }

        @Test
        fun `older mtime with the base hash is unchanged too`() {
            assertEquals(Change.UNCHANGED, localChange(local("a", "v1", lastModified = 1), b))
        }

        @Test
        fun `different hash is modified`() {
            assertEquals(Change.MODIFIED, localChange(local("a", "v2", lastModified = EDITED_MTIME), b))
            assertEquals(Change.MODIFIED, localChange(local("a", "longer"), b))
        }

        @Test
        fun `changed metadata without a hash is conservatively modified`() {
            assertEquals(Change.MODIFIED, localChange(local("a", "v1", lastModified = EDITED_MTIME, hashed = false), b))
        }
    }

    @Nested
    inner class Remote {
        private val b = base("a", "v1")

        @Test
        fun `absent new and deleted`() {
            assertEquals(Change.ABSENT, remoteChange(null, null))
            assertEquals(Change.NEW, remoteChange(remote("a", "v1"), null))
            assertEquals(Change.DELETED, remoteChange(null, b))
        }

        @Test
        fun `md5 decides when both hashes are known, whatever the id or version`() {
            assertEquals(Change.UNCHANGED, remoteChange(remote("a", "v1", fileId = "other", version = 9), b))
            assertEquals(Change.MODIFIED, remoteChange(remote("a", "v2"), b))
        }

        @Test
        fun `modifiedTime is never used for change detection`() {
            assertEquals(Change.UNCHANGED, remoteChange(remote("a", "v1", modifiedTime = Long.MAX_VALUE), b))
            assertEquals(Change.MODIFIED, remoteChange(remote("a", "v2", modifiedTime = 0), b))
        }

        @Test
        fun `without a hash falls back to file id and version`() {
            assertEquals(Change.UNCHANGED, remoteChange(remote("a", "v1", hashed = false), b))
            assertEquals(Change.MODIFIED, remoteChange(remote("a", "v1", version = 2, hashed = false), b))
            assertEquals(Change.MODIFIED, remoteChange(remote("a", "v1", fileId = "new-id", hashed = false), b))
        }

        @Test
        fun `base without a hash falls back to file id and version`() {
            val noHashBase = b.copy(md5 = null)
            assertEquals(Change.UNCHANGED, remoteChange(remote("a", "v2"), noHashBase))
            assertEquals(Change.MODIFIED, remoteChange(remote("a", "v2", version = 2), noHashBase))
        }
    }

    @Test
    fun `change helpers`() {
        assertTrue(Change.NEW.isPresentChange)
        assertTrue(Change.MODIFIED.isPresentChange)
        assertFalse(Change.UNCHANGED.isPresentChange)
        assertTrue(Change.UNCHANGED.isQuiet)
        assertTrue(Change.ABSENT.isQuiet)
        assertFalse(Change.DELETED.isQuiet)
        assertFalse(Change.DELETED.isPresentChange)
    }
}
