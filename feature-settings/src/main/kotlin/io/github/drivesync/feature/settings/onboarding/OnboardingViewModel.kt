package io.github.drivesync.feature.settings.onboarding

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.drive.auth.google.AuthorizationStep
import io.github.drivesync.core.drive.auth.google.GoogleAccount
import io.github.drivesync.core.drive.auth.google.GoogleAuth
import io.github.drivesync.core.drive.folders.DriveSyncFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sign-in flow: Credential Manager account picker → `drive.file` consent → find or create the tagged Drive Sync
 * root (PRD 3.10) → store the account. Consent screens are launched by the route via [consentRequests].
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val auth: GoogleAuth,
        private val folders: DriveSyncFolders,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(OnboardingUiState())
        val state: StateFlow<OnboardingUiState> = mutable.asStateFlow()

        /** A consent screen the route must launch; cleared once launched. */
        val consentRequests = MutableStateFlow<IntentSender?>(null)
        private var account: GoogleAccount? = null

        fun signIn(activityContext: Context) {
            mutable.update { it.copy(step = OnboardingStep.WORKING, settingUpDrive = false, error = null) }
            viewModelScope.launch {
                attempt {
                    val signedIn = auth.signIn(activityContext)
                    account = signedIn
                    when (val step = auth.authorize(signedIn)) {
                        AuthorizationStep.Authorized -> setUpDrive()
                        is AuthorizationStep.NeedsConsent -> consentRequests.value = step.intent.intentSender
                    }
                }
            }
        }

        /** Result of the consent screen; null data means the user backed out. */
        fun onConsentResult(data: Intent?) {
            consentRequests.value = null
            viewModelScope.launch {
                attempt {
                    when (auth.completeAuthorization(data)) {
                        AuthorizationStep.Authorized -> setUpDrive()
                        is AuthorizationStep.NeedsConsent -> fail("Drive access is needed to sync folders.")
                    }
                }
            }
        }

        fun onNotificationsAllowed(allowed: Boolean) = mutable.update { it.copy(notificationsAllowed = allowed) }

        private suspend fun setUpDrive() {
            mutable.update { it.copy(settingUpDrive = true) }
            val root = folders.findOrCreateRoot()
            val email = account?.email
            settings.setAccount(email, root)
            mutable.update { it.copy(step = OnboardingStep.PERMISSIONS, accountEmail = email) }
        }

        private suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Sign-in cancelled, no network, missing client id: all go back to the start with a message.
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

        private fun fail(message: String) = mutable.update { it.copy(step = OnboardingStep.WELCOME, error = message) }
    }
