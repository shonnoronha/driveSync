package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictKind
import io.github.drivesync.core.sync.plan.ConflictResolution
import io.github.drivesync.core.sync.plan.MoveReason
import io.github.drivesync.core.sync.plan.SkipReason
import io.github.drivesync.core.sync.plan.Skipped
import io.github.drivesync.core.sync.plan.SyncAction.CreateLocalFolder
import io.github.drivesync.core.sync.plan.SyncAction.Download
import io.github.drivesync.core.sync.plan.SyncAction.MoveRemote
import io.github.drivesync.core.sync.plan.SyncAction.TrashRemote
import io.github.drivesync.core.sync.plan.SyncAction.Upload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/** Drive quirks: Google-native files (FR-S12), duplicate names (FR-S13), and file/folder type clashes. */
class SyncPlannerRemoteQuirksTest {
    private val a = path("a.txt")

    @Nested
    inner class GoogleNative {
        @ParameterizedTest
        @ValueSource(strings = ["document", "spreadsheet", "presentation", "shortcut", "form", "drawing"])
        fun `is skipped and never acted on`(kind: String) {
            val doc = googleNative("Plan", kind)
            assertTrue(doc.isGoogleNative)
            val plan = planReady(emptyList(), listOf(doc), emptyList())
            assertActions(plan)
            assertSkipped(plan, Skipped(doc.path, SkipReason.GOOGLE_NATIVE))
        }

        @Test
        fun `Drive folders and binary files are not Google-native`() {
            assertFalse(remoteFolder("d").isGoogleNative)
            assertFalse(remote("a.txt", "x").isGoogleNative)
        }

        @ParameterizedTest
        @EnumSource(SyncDirection::class)
        fun `is skipped in every direction`(direction: SyncDirection) {
            val f = synced("a.txt", "x")
            val doc = googleNative("d/Sheet", "spreadsheet")
            val plan =
                planReady(
                    listOf(f.local, localFolder("d")),
                    listOf(f.remote, remoteFolder("d"), doc),
                    listOf(f.base, baseFolder("d")),
                    settings(direction, mirrorDeletions = true),
                )
            assertActions(plan)
            assertSkipped(plan, Skipped(doc.path, SkipReason.GOOGLE_NATIVE))
        }

        @Test
        fun `skipped entries are sorted by path`() {
            val plan = planReady(emptyList(), listOf(googleNative("b"), googleNative("a")), emptyList())
            assertEquals(listOf(path("a"), path("b")), plan.skipped.map { it.path })
        }

        @Test
        fun `a Google Doc does not hide a local file with the same name`() {
            val l = local("Plan", "bytes")
            assertActions(planReady(listOf(l), listOf(googleNative("Plan")), emptyList()), Upload(l.path, l, null))
        }

        @Test
        fun `a Google Doc name is avoided by conflict copies`() {
            val l = local("a.txt", "phone")
            val r = remote("a.txt", "drive")
            val plan = planReady(listOf(l), listOf(r, googleNative(conflictCopy("a.txt").value)), null)
            assertEquals(conflictCopy("a.txt", 2), plan.conflicts.single().conflictCopy)
        }
    }

    @Nested
    inner class Duplicates {
        private val r1 = remote("a.txt", "one", fileId = "r1")
        private val r2 = remote("a.txt", "two", fileId = "r2")
        private val copy = conflictCopy("a.txt")

        @Test
        fun `without a base the lowest id keeps the name and the other is renamed and downloaded`() {
            val plan = planReady(emptyList(), listOf(r2, r1), emptyList())
            assertActions(
                plan,
                MoveRemote(copy, a, r2, MoveReason.DUPLICATE_RENAME),
                Download(copy, r2.copy(path = copy), replaces = null),
                Download(a, r1, replaces = null),
            )
            assertConflicts(plan, Conflict(a, ConflictKind.DUPLICATE_REMOTE_NAME, ConflictResolution.KEPT_BOTH, copy))
        }

        @Test
        fun `the file known to the base keeps the name`() {
            val l = local("a.txt", "two")
            val b = base("a.txt", "two", remoteFileId = "r2")
            val plan = planReady(listOf(l), listOf(r1, r2), listOf(b))
            assertActions(
                plan,
                MoveRemote(copy, a, r1, MoveReason.DUPLICATE_RENAME),
                Download(copy, r1.copy(path = copy), replaces = null),
            )
        }

        @Test
        fun `a base id matching no duplicate falls back to the lowest id`() {
            val l = local("a.txt", "zero")
            val b = base("a.txt", "zero", remoteFileId = "gone")
            val plan = planReady(listOf(l), listOf(r2, r1), listOf(b))
            val rename = plan.actions.filterIsInstance<MoveRemote>().single()
            assertEquals(r2, rename.remote)
        }

        @Test
        fun `three duplicates get two distinct copies`() {
            val r3 = remote("a.txt", "three", fileId = "r3")
            val plan = planReady(emptyList(), listOf(r3, r1, r2), emptyList())
            val copy2 = conflictCopy("a.txt", 2)
            assertEquals(listOf(copy, copy2), plan.conflicts.map { it.conflictCopy })
            assertEquals(
                setOf(
                    MoveRemote(copy, a, r2, MoveReason.DUPLICATE_RENAME),
                    MoveRemote(copy2, a, r3, MoveReason.DUPLICATE_RENAME),
                    Download(copy, r2.copy(path = copy), replaces = null),
                    Download(copy2, r3.copy(path = copy2), replaces = null),
                    Download(a, r1, replaces = null),
                ),
                plan.actions.toSet(),
            )
            assertEquals(5, plan.actions.size)
        }

        @Test
        fun `download-only pairs rename duplicates too`() {
            val plan = planReady(emptyList(), listOf(r1, r2), emptyList(), settings(SyncDirection.DOWNLOAD_ONLY))
            assertEquals(1, plan.actions.count { it is MoveRemote })
            assertEquals(ConflictKind.DUPLICATE_REMOTE_NAME, plan.conflicts.single().kind)
        }

        @Test
        fun `upload-only pairs leave duplicates alone`() {
            val l = local("a.txt", "one")
            val b = base("a.txt", "one", remoteFileId = "r1")
            val plan = planReady(listOf(l), listOf(r1, r2), listOf(b), settings(SyncDirection.UPLOAD_ONLY))
            assertNothingToDo(plan)
            assertSkipped(plan, Skipped(a, SkipReason.DUPLICATE_REMOTE_NAME))
        }

        @Test
        fun `a renamed duplicate does not count as the primary file leaving`() {
            val f = path("f")
            val l = local("f/a.txt", "one")
            val p1 = remote("f/a.txt", "one", fileId = "r1")
            val p2 = remote("f/a.txt", "two", fileId = "r2")
            val plan =
                planReady(
                    listOf(localFolder("f"), l),
                    listOf(remoteFolder("f"), p1, p2),
                    listOf(baseFolder("f"), base("f/a.txt", "one", remoteFileId = "r1")),
                )
            assertTrue(plan.actions.none { it is TrashRemote }, plan.summary().toString())
            val rename = plan.actions.filterIsInstance<MoveRemote>().single()
            assertEquals(f, rename.path.parent)
        }

        @Test
        fun `a folder and a file with one name on Drive - the file is renamed`() {
            val folder = remoteFolder("x", fileId = "f1")
            val file = remote("x", "bytes", fileId = "f0")
            val xCopy = conflictCopy("x")
            val plan = planReady(emptyList(), listOf(file, folder), emptyList())
            assertActions(
                plan,
                CreateLocalFolder(path("x")),
                MoveRemote(xCopy, path("x"), file, MoveReason.DUPLICATE_RENAME),
                Download(xCopy, file.copy(path = xCopy), replaces = null),
            )
        }

        @Test
        fun `children of a hidden duplicate folder are never planned under a file`() {
            val f = synced("x", "bytes")
            val remoteFile = remote("x", "bytes", fileId = "id:x")
            val plan =
                planReady(
                    listOf(f.local),
                    listOf(remoteFile, remoteFolder("x", fileId = "zz-folder"), remote("x/inner.txt", "i")),
                    listOf(f.base),
                )
            assertTrue(plan.actions.none { path("x").isAncestorOf(it.path) }, plan.summary().toString())
        }

        @Test
        fun `mirrored folder delete keeps a folder holding a skipped duplicate`() {
            val p1 = remote("f/a.txt", "one", fileId = "r1")
            val p2 = remote("f/a.txt", "two", fileId = "r2")
            val rf = remoteFolder("f")
            val plan =
                planReady(
                    emptyList(),
                    listOf(rf, p1, p2),
                    listOf(baseFolder("f"), base("f/a.txt", "one", remoteFileId = "r1")),
                    settings(SyncDirection.UPLOAD_ONLY, mirrorDeletions = true),
                )
            assertFalse(plan.actions.contains(TrashRemote(path("f"), rf)), plan.summary().toString())
        }
    }

    @Nested
    inner class TypeMismatch {
        @Test
        fun `local file and Drive folder with one name - pending, nothing planned below it`() {
            val plan =
                planReady(
                    listOf(local("x", "bytes")),
                    listOf(remoteFolder("x"), remote("x/y.txt", "y"), remoteFolder("x/sub")),
                    null,
                )
            assertActions(plan)
            assertConflicts(plan, Conflict(path("x"), ConflictKind.TYPE_MISMATCH, ConflictResolution.PENDING))
        }

        @Test
        fun `file replaced by a folder on the phone - pending`() {
            val f = synced("x", "bytes")
            val plan = planReady(listOf(localFolder("x"), local("x/new.txt", "n")), listOf(f.remote), listOf(f.base))
            assertActions(plan)
            assertConflicts(plan, Conflict(path("x"), ConflictKind.TYPE_MISMATCH, ConflictResolution.PENDING))
        }

        @Test
        fun `folder replaced by a file on Drive - pending`() {
            val plan = planReady(listOf(localFolder("x")), listOf(remote("x", "bytes")), listOf(baseFolder("x")))
            assertActions(plan)
            assertConflicts(plan, Conflict(path("x"), ConflictKind.TYPE_MISMATCH, ConflictResolution.PENDING))
        }

        @Test
        fun `a mismatch does not block its siblings`() {
            val sibling = local("x2.txt", "s")
            val plan = planReady(listOf(local("x", "bytes"), sibling), listOf(remoteFolder("x")), null)
            assertActions(plan, Upload(sibling.path, sibling, replaces = null))
            assertEquals(ConflictKind.TYPE_MISMATCH, plan.conflicts.single().kind)
        }
    }
}
