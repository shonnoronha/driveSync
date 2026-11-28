package io.github.drivesync.core.work.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.work.R
import io.github.drivesync.core.work.TileStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Quick Settings tile (FR-M4): shows whether the folders are synced and starts a sync on tap. When something needs
 * the user (sign-in, a missing folder, deletes to confirm) a tap opens the app instead.
 */
class SyncTileService : TileService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun pairs(): PairRepository

        fun settings(): SettingsRepository

        fun workManager(): WorkManager

        fun scheduler(): SyncScheduler
    }

    private val deps by lazy { EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var status = TileStatus.SET_UP

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        val signedIn = deps.settings().settings.map { it.accountEmail != null }
        val syncing =
            deps.workManager().getWorkInfosByTagFlow(SyncScheduler.TAG).map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING }
            }
        listening =
            combine(signedIn, deps.pairs().observePairs(), syncing) { account, pairs, running ->
                TileStatus.of(account, pairs, running) to TileStatus.allSyncedSince(pairs)
            }.distinctUntilChanged()
                .onEach { (now, since) -> show(now, since) }
                .launchIn(scope)
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (status.tapSyncs) {
            deps.scheduler().syncNow(manual = true)
            show(TileStatus.SYNCING, null)
        } else if (isLocked) {
            unlockAndRun { openApp() }
        } else {
            openApp()
        }
    }

    private fun show(
        now: TileStatus,
        syncedSince: Long?,
    ) {
        status = now
        val tile = qsTile ?: return
        val subtitle = subtitle(now, syncedSince)
        tile.state = if (now == TileStatus.SYNCING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = getString(R.string.tile_label) + ", " + subtitle
        tile.updateTile()
    }

    private fun subtitle(
        now: TileStatus,
        syncedSince: Long?,
    ): String =
        when (now) {
            TileStatus.SET_UP -> {
                getString(R.string.tile_set_up)
            }

            TileStatus.SYNCING -> {
                getString(R.string.tile_syncing)
            }

            TileStatus.NEEDS_YOU -> {
                getString(R.string.tile_needs_you)
            }

            TileStatus.PAUSED -> {
                getString(R.string.tile_paused)
            }

            TileStatus.FAILED -> {
                getString(R.string.tile_failed)
            }

            TileStatus.IDLE -> {
                syncedSince?.let { getString(R.string.tile_synced, relative(it)) } ?: getString(R.string.tile_tap_sync)
            }
        }

    private fun relative(time: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
