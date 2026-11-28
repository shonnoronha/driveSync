package io.github.drivesync.core.drive.api

import java.io.IOException

/**
 * A Drive request failed. Subclasses are [IOException]s so they also travel safely through OkHttp internals.
 * The executor isolates any of these to the single action that hit it (FR-S9).
 */
sealed class DriveException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class NotFound(
        message: String,
    ) : DriveException(message)

    /** `storageQuotaExceeded`: the caller should stop uploads and notify the user (PRD 6.3). */
    class QuotaExceeded(
        message: String,
    ) : DriveException(message)

    /**
     * Drive refused this one file: it belongs to someone else, or its owner disabled downloads. Only that file
     * fails; the rest of the folder keeps syncing.
     */
    class NoPermission(
        message: String,
    ) : DriveException(message)

    /** Still rate limited after every retry. */
    class RateLimited(
        message: String,
    ) : DriveException(message)

    class Http(
        val code: Int,
        val reason: String?,
        message: String,
    ) : DriveException("HTTP $code${reason?.let { " ($it)" }.orEmpty()}: $message")

    /** The server answered with something this client cannot interpret. */
    class Protocol(
        message: String,
    ) : DriveException(message)
}
