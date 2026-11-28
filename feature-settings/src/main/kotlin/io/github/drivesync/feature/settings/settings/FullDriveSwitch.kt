package io.github.drivesync.feature.settings.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/** Google's side of the full Drive switch, so the flow can be tested without Play services. */
interface FullDriveGoogle<Screen> {
    /** Asks for the full `drive` scope: null when it is already granted, else the consent screen to show. */
    suspend fun request(): Screen?

    /** Reads what the consent screen returned: true when the full scope is now granted. */
    suspend fun finish(result: Any?): Boolean
}

enum class FullDriveMessage {
    /** The user closed the consent screen or unticked Drive access. */
    NOT_GRANTED,

    /** Google refused the request, usually because the Cloud project lacks the `drive` scope. */
    FAILED,
}

/**
 * The full Drive access switch (PRD 6.4). Turning on asks Google for the scope first and saves the mode only once it
 * is granted; a refusal leaves the app on `drive.file`. Turning off needs no consent.
 */
class FullDriveSwitch<Screen>(
    private val available: Boolean,
    private val google: FullDriveGoogle<Screen>,
    private val enable: suspend () -> Unit,
    private val disable: suspend () -> Unit,
) {
    private val consentScreen = MutableStateFlow<Screen?>(null)
    private val lastMessage = MutableStateFlow<FullDriveMessage?>(null)

    /** A consent screen the UI must launch, then pass its result to [consentResult]. */
    val consent: StateFlow<Screen?> = consentScreen.asStateFlow()
    val message: StateFlow<FullDriveMessage?> = lastMessage.asStateFlow()

    /** Also renews a lapsed grant when full access is already on. */
    suspend fun turnOn() {
        if (!available) return
        lastMessage.value = null
        val screen =
            try {
                google.request()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception, // Play services reports refusals as exceptions.
            ) {
                lastMessage.value = FullDriveMessage.FAILED
                return
            }
        if (screen == null) enable() else consentScreen.value = screen
    }

    suspend fun consentResult(result: Any?) {
        consentScreen.value = null
        val granted =
            try {
                google.finish(result)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception, // A closed consent screen throws.
            ) {
                false
            }
        if (granted) enable() else lastMessage.value = FullDriveMessage.NOT_GRANTED
    }

    suspend fun turnOff() {
        lastMessage.value = null
        disable()
    }
}
