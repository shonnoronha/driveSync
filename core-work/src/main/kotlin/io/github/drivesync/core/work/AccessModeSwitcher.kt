package io.github.drivesync.core.work

import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.sync.store.AccessMode
import javax.inject.Inject

/**
 * Turns full Drive access (PRD 6.4) on or off. The Google consent is the caller's job: [enable] runs only after the
 * user granted the `drive` scope. Every Drive request reads the saved mode, so the new scopes apply at once.
 */
class AccessModeSwitcher
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val pairs: PairRepository,
        private val runs: SyncRuns,
    ) {
        /**
         * Files added from the Drive website or other apps become visible; they have no base, so they download.
         * Also used after renewing the grant (it may lapse while the Cloud project is in Testing): syncs again.
         */
        suspend fun enable() {
            settings.setAccessMode(AccessMode.FULL_DRIVE)
            runs.restart(settings.current())
        }

        /**
         * Back to `drive.file`. Files the app downloaded from other apps become invisible again, which a normal run
         * would read as "deleted on Drive" and move to the phone's recycle folder. So every pair's base is dropped
         * first and the next run only merges (PRD 3.9 rule 7). Order matters: no run may be in flight while the
         * base is reset, and the mode is saved only after the reset, so a crash in between stays safe.
         * The `drive` grant is not revoked; the app just stops asking for it.
         */
        suspend fun disable() {
            if (settings.current().accessMode == AccessMode.APP_FOLDER) return
            runs.stopAll()
            pairs.pairs().forEach { pairs.mergeOnlyNextRun(it.id) }
            settings.setAccessMode(AccessMode.APP_FOLDER)
            runs.restart(settings.current())
        }
    }
