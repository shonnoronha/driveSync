package io.github.drivesync.feature.settings.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Onboarding wired to its ViewModel, the consent screen, the notification permission and battery settings. */
@Composable
fun OnboardingRoute(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consent by viewModel.consentRequests.collectAsStateWithLifecycle()
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onConsentResult(it.data)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
            viewModel::onNotificationsAllowed,
        )
    LaunchedEffect(consent) { consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
    LaunchedEffect(Unit) {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        viewModel.onNotificationsAllowed(granted)
    }
    OnboardingScreen(
        state = state,
        actions =
            OnboardingActions(
                onSignIn = { viewModel.signIn(context) },
                onAllowNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                // The settings list, not a direct exemption request (PRD 6.1: Play policy).
                onOpenBatterySettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
                onDone = onDone,
            ),
    )
}
