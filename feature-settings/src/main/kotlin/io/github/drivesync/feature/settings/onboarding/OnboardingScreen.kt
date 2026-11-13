package io.github.drivesync.feature.settings.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.feature.settings.R

/** Onboarding (PRD 8.1): what the app does, Google sign-in plus the Drive Sync folder, then permissions. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> Welcome(state, actions)
                OnboardingStep.WORKING -> Working(state)
                OnboardingStep.PERMISSIONS -> Permissions(state, actions)
            }
        }
    }
}

@Composable
private fun Welcome(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Spacer(Modifier.size(24.dp))
    Icon(
        Icons.Outlined.CloudSync,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
    Point(Icons.Outlined.Folder, stringResource(R.string.onboarding_point_folders))
    Point(Icons.Outlined.Sync, stringResource(R.string.onboarding_point_drive))
    Point(Icons.Outlined.RestoreFromTrash, stringResource(R.string.onboarding_point_safe))
    Point(Icons.Outlined.Lock, stringResource(R.string.onboarding_point_private))
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = actions.onSignIn, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_sign_in))
    }
}

@Composable
private fun Working(state: OnboardingUiState) {
    Column(
        Modifier.fillMaxWidth().padding(top = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CircularProgressIndicator()
        val text = if (state.settingUpDrive) R.string.onboarding_working_drive else R.string.onboarding_working_sign_in
        Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Permissions(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Spacer(Modifier.size(24.dp))
    Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineMedium)
    state.accountEmail?.let {
        Text(
            stringResource(R.string.onboarding_signed_in_as, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PermissionCard(
        Icons.Outlined.Notifications,
        stringResource(R.string.onboarding_notifications),
        stringResource(R.string.onboarding_notifications_body),
    ) {
        if (state.notificationsAllowed) {
            OutlinedButton(
                onClick = {},
                enabled = false,
            ) { Text(stringResource(R.string.onboarding_notifications_allowed)) }
        } else {
            FilledTonalButton(onClick = actions.onAllowNotifications) {
                Text(stringResource(R.string.onboarding_notifications_allow))
            }
        }
    }
    PermissionCard(
        Icons.Outlined.BatteryChargingFull,
        stringResource(R.string.onboarding_battery),
        stringResource(R.string.onboarding_battery_body),
    ) {
        OutlinedButton(
            onClick = actions.onOpenBatterySettings,
        ) { Text(stringResource(R.string.onboarding_battery_open)) }
    }
    Button(
        onClick = actions.onDone,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onboarding_done)) }
}

@Composable
private fun Point(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}
