package io.github.drivesync.core.drive.auth.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drivesync.core.drive.BuildConfig
import io.github.drivesync.core.drive.auth.AuthProvider
import io.github.drivesync.core.drive.auth.AuthRequiredException
import io.github.drivesync.core.drive.auth.DriveScopes
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Identity Services: Credential Manager for sign-in (FR-A1) and `AuthorizationClient` for the
 * `drive.file` scope (FR-A2). Play services caches and refreshes access tokens; [accessToken] just asks again.
 */
@Singleton
class GoogleAuth
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AuthProvider {
        private val authorizationClient: AuthorizationClient = Identity.getAuthorizationClient(context)
        private val driveScopes = listOf(Scope(DriveScopes.DRIVE_FILE))

        /** Background use: never shows UI. Throws [AuthRequiredException] when the user must consent again. */
        override suspend fun accessToken(): String {
            val result =
                try {
                    authorizationClient.authorize(authorizationRequest()).await()
                } catch (e: ApiException) {
                    throw AuthRequiredException("Drive authorization failed: ${e.statusCode}", e)
                }
            if (result.hasResolution()) throw AuthRequiredException("Drive access needs the user's consent")
            return result.accessToken ?: throw AuthRequiredException("No Drive access token was granted")
        }

        override suspend fun invalidate(token: String) {
            authorizationClient.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }

        /**
         * Shows the Google account picker (FR-A1). [activityContext] must be an Activity. Throws
         * [androidx.credentials.exceptions.GetCredentialException] when the user cancels.
         */
        suspend fun signIn(activityContext: Context): GoogleAccount {
            check(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotEmpty()) {
                "drivesync.googleServerClientId is missing from local.properties"
            }
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential =
                CredentialManager
                    .create(
                        activityContext,
                    ).getCredential(activityContext, request)
                    .credential
            check(
                credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            ) { "Unexpected credential type ${credential.type}" }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            val email = checkNotNull(google.email) { "Google sign-in returned no email address" }
            return GoogleAccount(email = email, displayName = google.displayName)
        }

        /**
         * Asks for `drive.file` (FR-A2). When consent is needed, launch [AuthorizationStep.NeedsConsent.intent]
         * and pass the returned data to [completeAuthorization].
         */
        suspend fun authorize(account: GoogleAccount?): AuthorizationStep =
            authorizationClient.authorize(authorizationRequest(account)).await().toStep()

        fun completeAuthorization(data: Intent?): AuthorizationStep =
            authorizationClient.getAuthorizationResultFromIntent(data).toStep()

        /** FR-A5: revokes Drive access and forgets the signed-in credential. App data is wiped by the caller. */
        suspend fun signOut(account: GoogleAccount) {
            val revoke = RevokeAccessRequest.builder().setAccount(account.toAndroidAccount()).setScopes(driveScopes)
            authorizationClient.revokeAccess(revoke.build()).await()
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }

        private fun authorizationRequest(account: GoogleAccount? = null): AuthorizationRequest =
            AuthorizationRequest
                .builder()
                .setRequestedScopes(driveScopes)
                .apply { account?.let { setAccount(it.toAndroidAccount()) } }
                .build()

        private fun AuthorizationResult.toStep(): AuthorizationStep {
            val consent = pendingIntent
            return when {
                hasResolution() && consent != null -> AuthorizationStep.NeedsConsent(consent)
                else -> AuthorizationStep.Authorized
            }
        }

        private fun GoogleAccount.toAndroidAccount() = Account(email, GOOGLE_ACCOUNT_TYPE)

        private companion object {
            const val GOOGLE_ACCOUNT_TYPE = "com.google"
        }
    }

data class GoogleAccount(
    val email: String,
    val displayName: String?,
)

sealed interface AuthorizationStep {
    data object Authorized : AuthorizationStep

    data class NeedsConsent(
        val intent: PendingIntent,
    ) : AuthorizationStep
}
