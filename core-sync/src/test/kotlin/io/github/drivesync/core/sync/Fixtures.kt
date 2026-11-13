package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.BaseEntry
import io.github.drivesync.core.sync.model.BaseSnapshot
import io.github.drivesync.core.sync.model.ConflictPolicy
import io.github.drivesync.core.sync.model.LocalEntry
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.model.RemoteEntry
import io.github.drivesync.core.sync.model.Snapshot
import io.github.drivesync.core.sync.model.SyncDirection
import io.github.drivesync.core.sync.plan.Conflict
import io.github.drivesync.core.sync.plan.ConflictStamp
import io.github.drivesync.core.sync.plan.PlanInput
import io.github.drivesync.core.sync.plan.PlanResult
import io.github.drivesync.core.sync.plan.Skipped
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.plan.SyncPlan
import io.github.drivesync.core.sync.plan.SyncPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import java.security.MessageDigest
import java.time.LocalDateTime

/*
 * Test DSL for the planner. Conventions:
 * - md5 and size are derived from a content string, so "same content" means "same string".
 * - Local lastModified and the base's localLastModified default to DEFAULT_MTIME.
 * - Remote fileId defaults to "id:<path>", version to 1; the base points at the same id/version.
 * So local("a", "v1"), remote("a", "v1"), base("a", "v1") is a file that is in sync on both sides.
 */

const val DEFAULT_MTIME = 1_000L
const val EDITED_MTIME = 2_000L

val testStamp = ConflictStamp(LocalDateTime.of(2026, 11, 12, 14, 30, 59), "Pixel")
const val STAMP_LABEL = "conflict 2026-11-12 14-30 Pixel"

fun md5Of(content: String): String =
    MessageDigest
        .getInstance("MD5")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun path(raw: String): RelPath = RelPath.of(raw)

fun local(
    path: String,
    content: String,
    lastModified: Long = DEFAULT_MTIME,
    hashed: Boolean = true,
): LocalEntry =
    LocalEntry(
        path = RelPath.of(path),
        isFolder = false,
        size = content.length.toLong(),
        lastModified = lastModified,
        md5 = if (hashed) md5Of(content) else null,
    )

fun localFolder(path: String): LocalEntry = LocalEntry(RelPath.of(path), isFolder = true)

fun remote(
    path: String,
    content: String,
    fileId: String = "id:$path",
    version: Long = 1,
    modifiedTime: Long = DEFAULT_MTIME,
    hashed: Boolean = true,
): RemoteEntry =
    RemoteEntry(
        path = RelPath.of(path),
        fileId = fileId,
        isFolder = false,
        size = content.length.toLong(),
        md5 = if (hashed) md5Of(content) else null,
        version = version,
        modifiedTime = modifiedTime,
    )

fun remoteFolder(
    path: String,
    fileId: String = "id:$path",
): RemoteEntry = RemoteEntry(RelPath.of(path), fileId = fileId, isFolder = true)

fun googleNative(
    path: String,
    kind: String = "document",
    fileId: String = "id:$path",
): RemoteEntry =
    RemoteEntry(
        path = RelPath.of(path),
        fileId = fileId,
        isFolder = false,
        md5 = null,
        mimeType = "application/vnd.google-apps.$kind",
    )

fun base(
    path: String,
    content: String,
    localLastModified: Long = DEFAULT_MTIME,
    remoteFileId: String = "id:$path",
    remoteVersion: Long = 1,
): BaseEntry =
    BaseEntry(
        path = RelPath.of(path),
        isFolder = false,
        md5 = md5Of(content),
        localSize = content.length.toLong(),
        localLastModified = localLastModified,
        remoteFileId = remoteFileId,
        remoteVersion = remoteVersion,
    )

fun baseFolder(
    path: String,
    remoteFileId: String = "id:$path",
): BaseEntry = BaseEntry(RelPath.of(path), isFolder = true, remoteFileId = remoteFileId)

/** The three entries of a file that is fully in sync. */
data class SyncedFile(
    val local: LocalEntry,
    val remote: RemoteEntry,
    val base: BaseEntry,
)

fun synced(
    path: String,
    content: String,
): SyncedFile = SyncedFile(local(path, content), remote(path, content), base(path, content))

fun settings(
    direction: SyncDirection = SyncDirection.TWO_WAY,
    mirrorDeletions: Boolean = false,
): PairSettings = PairSettings(direction = direction, mirrorDeletions = mirrorDeletions)

/** Name of the n-th conflict copy of [raw] for [testStamp]. */
fun conflictCopy(
    raw: String,
    n: Int = 1,
): RelPath {
    val original = RelPath.of(raw)
    val name = original.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val suffix = if (n > 1) " $n" else ""
    return original.sibling("$stem ($STAMP_LABEL$suffix)$ext")
}

fun planOf(
    local: List<LocalEntry>,
    remote: List<RemoteEntry>,
    base: List<BaseEntry>?,
    settings: PairSettings = PairSettings(),
    deletionsConfirmed: Boolean = false,
    resolutions: Map<RelPath, ConflictPolicy> = emptyMap(),
): PlanResult =
    SyncPlanner.plan(
        PlanInput(
            local = Snapshot.Available(local),
            remote = Snapshot.Available(remote),
            base = base?.let(::BaseSnapshot),
            settings = settings,
            conflictStamp = testStamp,
            deletionsConfirmed = deletionsConfirmed,
            resolutions = resolutions,
        ),
    )

/** An unrelated, in-sync file. It produces no action and keeps a side from looking "suddenly empty". */
val anchor = synced("zz-anchor.bin", "anchor")

/**
 * Plans with [anchor] added when a base exists (so deleting the file under test does not pause the pair)
 * and asserts the result is [PlanResult.Ready].
 */
fun planReady(
    local: List<LocalEntry>,
    remote: List<RemoteEntry>,
    base: List<BaseEntry>?,
    settings: PairSettings = PairSettings(),
    resolutions: Map<RelPath, ConflictPolicy> = emptyMap(),
): SyncPlan {
    val withAnchor = base != null
    return planOf(
        local = if (withAnchor) local + anchor.local else local,
        remote = if (withAnchor) remote + anchor.remote else remote,
        base = if (withAnchor) base.orEmpty() + anchor.base else null,
        settings = settings,
        resolutions = resolutions,
    ).ready()
}

fun PlanResult.ready(): SyncPlan = assertInstanceOf(PlanResult.Ready::class.java, this).plan

fun assertActions(
    plan: SyncPlan,
    vararg expected: SyncAction,
) = assertEquals(expected.toList(), plan.actions)

fun assertConflicts(
    plan: SyncPlan,
    vararg expected: Conflict,
) = assertEquals(expected.toList(), plan.conflicts)

fun assertSkipped(
    plan: SyncPlan,
    vararg expected: Skipped,
) = assertEquals(expected.toList(), plan.skipped)

fun assertNothingToDo(plan: SyncPlan) {
    assertEquals(emptyList<SyncAction>(), plan.actions)
    assertEquals(emptyList<Conflict>(), plan.conflicts)
}

/** Compact "Type:path" rendering for order assertions. */
fun SyncPlan.summary(): List<String> = actions.map { "${it::class.simpleName}:${it.path}" }
