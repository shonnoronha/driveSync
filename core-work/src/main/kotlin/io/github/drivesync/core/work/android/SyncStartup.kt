package io.github.drivesync.core.work.android

import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.work.MediaFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps WorkManager in line with the settings and pairs: the periodic run follows the interval and network rules,
 * and the MediaStore trigger is armed only while a signed-in user has a media pair. Started by the Application.
 */
class SyncStartup
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val pairs: PairRepository,
        private val scheduler: SyncScheduler,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                combine(settings.settings, pairs.observePairs()) { app, list ->
                    val signedIn = app.accountEmail != null
                    Triple(
                        app,
                        signedIn,
                        signedIn && list.any { it.enabled && MediaFolders.isMediaTree(it.localTreeUri) },
                    )
                }.distinctUntilChanged().collect { (app, signedIn, watchMedia) ->
                    if (!signedIn) {
                        scheduler.cancelAll()
                        return@collect
                    }
                    scheduler.schedulePeriodic(app)
                    if (watchMedia) scheduler.watchMedia(app) else scheduler.stopWatchingMedia()
                }
            }
        }
    }
