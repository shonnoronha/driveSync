package io.github.drivesync.core.drive.auth

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

/** OAuth scopes (PRD 6.4). v1 asks for `drive.file` only. */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
}
