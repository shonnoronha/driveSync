package io.github.drivesync.core.sync.store

/** How the app reaches Drive (PRD 3.8, 6.4). The user opts in to [FULL_DRIVE] in Settings. */
enum class AccessMode {
    /** `drive.file` scope; every pair lives inside the app-created "Drive Sync" root. The default. */
    APP_FOLDER,

    /**
     * Advanced mode: the full `drive` scope with the user's own OAuth client, so files added to a pair's folder
     * from the Drive website or other apps are synced too. Pairs still live inside the Drive Sync root.
     */
    FULL_DRIVE,
}
