package io.github.drivesync.core.sync.store

/** How the app reaches Drive (PRD 3.8, 6.4). v1 ships only [APP_FOLDER]. */
enum class AccessMode {
    /** `drive.file` scope; every pair lives inside the app-created "Drive Sync" root. */
    APP_FOLDER,

    /** Future Advanced mode: full `drive` scope, any existing folder, user's own OAuth client. */
    FULL_DRIVE,
}
