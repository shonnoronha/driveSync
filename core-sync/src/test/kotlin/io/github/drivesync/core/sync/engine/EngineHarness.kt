package io.github.drivesync.core.sync.engine

import io.github.drivesync.core.sync.conflictCopy
import io.github.drivesync.core.sync.fakes.FakeLocalStore
import io.github.drivesync.core.sync.fakes.FakeRemoteStore
import io.github.drivesync.core.sync.fakes.InMemoryBaseStore
import io.github.drivesync.core.sync.fakes.LocalOp
import io.github.drivesync.core.sync.fakes.RemoteOp
import io.github.drivesync.core.sync.model.PairSettings
import io.github.drivesync.core.sync.model.RelPath
import io.github.drivesync.core.sync.plan.SyncAction
import io.github.drivesync.core.sync.testStamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.coroutines.cancellation.CancellationException

/*
 * End-to-end harness: one phone = one pair (local tree + base) syncing against a Drive folder that several
 * phones may share. Every store operation is recorded so tests can assert which side was written.
 */

val LOCAL_WRITES =
    setOf(
        LocalOp.WRITE_TEMP,
        LocalOp.COMMIT_TEMP,
        LocalOp.DELETE_TEMP,
        LocalOp.CREATE_FOLDER,
        LocalOp.MOVE,
        LocalOp.RECYCLE,
    )
val REMOTE_WRITES = setOf(RemoteOp.CREATE_FOLDER, RemoteOp.UPLOAD, RemoteOp.MOVE, RemoteOp.TRASH)

typealias LocalHook = suspend (LocalOp, RelPath?) -> Unit
typealias RemoteHook = suspend (RemoteOp, RelPath?) -> Unit

class Phone(
    val remote: FakeRemoteStore = FakeRemoteStore(),
    localStart: Long = 1_000_000L,
) {
    val local = FakeLocalStore(localStart)
    val base = InMemoryBaseStore()
    private var engine = SyncEngine(local, remote, base)

    /** Operations of the latest [sync] call, as (op, path). */
    val localOps = mutableListOf<Pair<LocalOp, String?>>()
    val remoteOps = mutableListOf<Pair<RemoteOp, String?>>()

    /** Test hooks, called after the operation is recorded. */
    var onLocal: LocalHook? = null
    var onRemote: RemoteHook? = null

    suspend fun sync(
        settings: PairSettings = PairSettings(),
        confirm: Boolean = false,
    ): SyncOutcome {
        localOps.clear()
        remoteOps.clear()
        local.beforeOp = { op, p ->
            localOps += op to p?.value
            onLocal?.invoke(op, p)
        }
        // Installed per run because phones may share one Drive.
        remote.beforeOp = { op, p ->
            remoteOps += op to p?.value
            onRemote?.invoke(op, p)
        }
        return engine.sync(settings, testStamp, confirm)
    }

    /** Runs a sync that must complete and returns its outcome. */
    suspend fun run(settings: PairSettings = PairSettings()): SyncOutcome.Completed =
        assertInstanceOf(SyncOutcome.Completed::class.java, sync(settings))

    /** Runs a sync where every action must succeed. */
    suspend fun runClean(settings: PairSettings = PairSettings()): SyncOutcome.Completed {
        val outcome = run(settings)
        val notDone = outcome.report.results.filter { it.outcome != ActionOutcome.Done }
        assertEquals(emptyList<ActionResult>(), notDone)
        return outcome
    }

    /** Runs a sync that must be killed by a [CancellationException] thrown from a hook. */
    suspend fun crash(settings: PairSettings = PairSettings()) {
        val error = runCatching { sync(settings) }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, error)
    }

    /** Process restart: a fresh engine on the same persistent stores, no hooks. */
    fun restart() {
        onLocal = null
        onRemote = null
        base.beforeCommit = null
        engine = SyncEngine(local, remote, base)
    }

    /** Rule 2: once synced, the next run plans nothing and writes nothing. */
    suspend fun assertConverged(settings: PairSettings = PairSettings()) {
        val outcome = run(settings)
        assertEquals(emptyList<SyncAction>(), outcome.plan.actions, "second run must be empty")
        assertEquals(emptyList<Any>(), outcome.plan.conflicts)
        assertEquals(emptyList<Any>(), writes())
        assertNoTempFiles()
    }

    /** Two-way: both sides hold exactly the same files and folders, without Drive duplicates. */
    fun assertMirrored() {
        assertNoDuplicates()
        assertEquals(local.files(), remote.files().mapValues { it.value.single() })
        assertEquals(local.folders(), remote.folders())
    }

    fun assertNoDuplicates() {
        val dupes = remote.files().filterValues { it.size > 1 }
        assertTrue(dupes.isEmpty(), "Drive duplicates: $dupes")
    }

    fun assertNoTempFiles() = assertEquals(emptySet<String>(), local.tempFiles())

    fun writes(): List<String> =
        localOps.filter { it.first in LOCAL_WRITES }.map { "local ${it.first} ${it.second}" } +
            remoteOps.filter { it.first in REMOTE_WRITES }.map { "drive ${it.first} ${it.second}" }

    fun transfersOf(path: String): List<RemoteOp> =
        remoteOps.filter { it.second == path && (it.first == RemoteOp.UPLOAD || it.first == RemoteOp.DOWNLOAD) }.map {
            it.first
        }
}

/** A hook that runs [block] the first time [op] hits [path]. */
fun <O> once(
    op: O,
    path: String,
    block: suspend () -> Unit,
): suspend (O, RelPath?) -> Unit {
    var fired = false
    return { o, p ->
        if (!fired && o == op && p?.value == path) {
            fired = true
            block()
        }
    }
}

inline fun <reified A : SyncAction> SyncOutcome.Completed.outcomeOf(path: String): ActionOutcome =
    report.results.single { it.action is A && it.action.path.value == path }.outcome

fun killed(): Nothing = throw CancellationException("process killed")

fun copyOf(path: String): String = conflictCopy(path).value
