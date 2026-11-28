package io.github.drivesync.core.drive.auth

import io.github.drivesync.core.drive.BuildConfig
import io.github.drivesync.core.sync.store.AccessMode
import java.io.IOException

/**
 * Supplies OAuth access tokens for Drive. Sign-in UI lives elsewhere; this is what background syncs use.
 * Hides Google Identity Services so tests and a future Advanced mode can swap it (PRD 6.4, FR-A6).
 */
interface AuthProvider {
    /** A currently valid access token. Throws [AuthRequiredException] when only the user can fix it (FR-A4). */
    suspend fun accessToken(): String

    /** Drops a token Drive rejected, so the next [accessToken] call fetches a fresh one. */
    suspend fun invalidate(token: String)
}

/** Authorization is missing, revoked or needs consent: pause the pairs and prompt the user (FR-A4). */
class AuthRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * OAuth scopes (PRD 6.4). The default asks for `drive.file` only. Full Drive access adds the restricted `drive`
 * scope, which the user must add to their own Google Cloud project first.
 */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE = "https://www.googleapis.com/auth/drive"

    /** `drive.file` stays in the full set, so switching back never needs a new consent screen. */
    fun scopesFor(mode: AccessMode): List<String> =
        when (mode) {
            AccessMode.APP_FOLDER -> listOf(DRIVE_FILE)
            AccessMode.FULL_DRIVE -> listOf(DRIVE_FILE, DRIVE)
        }

    /** Everything the app may ever have been granted; sign-out revokes all of it. */
    val ALL: List<String> = scopesFor(AccessMode.FULL_DRIVE)

    /** This build's OAuth client has the `drive` scope (`drivesync.fullDriveScope=true` in local.properties). */
    val fullDriveConfigured: Boolean get() = BuildConfig.FULL_DRIVE_SCOPE_CONFIGURED
}

/** The saved access mode. Implemented over the settings store, which core-drive does not depend on. */
fun interface AccessModeSource {
    suspend fun current(): AccessMode
}
