package io.github.drivesync.feature.settings.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.ui.components.SettingsGroup
import io.github.drivesync.core.ui.components.SettingsRow
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.Tokens
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
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(vertical = Tokens.gapXl),
            verticalArrangement = Arrangement.spacedBy(Tokens.gapLg),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> Welcome(state, actions)
                OnboardingStep.WORKING -> Working(state)
                OnboardingStep.PERMISSIONS -> Permissions(state, actions)
            }
        }
    }
}

private val inset = Modifier.padding(horizontal = Tokens.pageInset + Tokens.gapXs)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(icon: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialShapes.Cookie9Sided.toShape(),
        modifier = inset.size(96.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) { SymIcon(icon, contentDescription = null, modifier = Modifier.size(44.dp)) }
    }
}

@Composable
private fun Welcome(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Spacer(Modifier.height(Tokens.gapXl))
    Hero(Sym.CloudSync)
    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.displaySmall, modifier = inset)
    SettingsGroup {
        item { SettingsRow(title = stringResource(R.string.onboarding_point_folders), icon = Sym.Folder) }
        item { SettingsRow(title = stringResource(R.string.onboarding_point_drive), icon = Sym.SyncAlt) }
        item { SettingsRow(title = stringResource(R.string.onboarding_point_safe), icon = Sym.RestoreFromTrash) }
        item { SettingsRow(title = stringResource(R.string.onboarding_point_private), icon = Sym.Lock) }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = inset) }
    Button(onClick = actions.onSignIn, modifier = inset.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.onboarding_sign_in), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Working(state: OnboardingUiState) {
    Column(
        Modifier.fillMaxWidth().padding(top = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.gapXl),
    ) {
        LoadingIndicator(Modifier.size(72.dp))
        val text = if (state.settingUpDrive) R.string.onboarding_working_drive else R.string.onboarding_working_sign_in
        Text(stringResource(text), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Permissions(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Spacer(Modifier.height(Tokens.gapXl))
    Hero(Sym.Notifications)
    Text(
        stringResource(R.string.onboarding_permissions_title),
        style = MaterialTheme.typography.displaySmall,
        modifier = inset,
    )
    state.accountEmail?.let {
        Text(
            stringResource(R.string.onboarding_signed_in_as, it),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = inset,
        )
    }
    SettingsGroup {
        item {
            SettingsRow(
                title = stringResource(R.string.onboarding_notifications),
                subtitle = stringResource(R.string.onboarding_notifications_body),
                icon = Sym.Notifications,
                footer = {
                    if (state.notificationsAllowed) {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                        ) { Text(stringResource(R.string.onboarding_notifications_allowed)) }
                    } else {
                        FilledTonalButton(onClick = actions.onAllowNotifications) {
                            Text(stringResource(R.string.onboarding_notifications_allow))
                        }
                    }
                },
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.onboarding_battery),
                subtitle = stringResource(R.string.onboarding_battery_body),
                icon = Sym.Battery,
                footer = {
                    FilledTonalButton(
                        onClick = actions.onOpenBatterySettings,
                    ) { Text(stringResource(R.string.onboarding_battery_open)) }
                },
            )
        }
    }
    Button(onClick = actions.onDone, modifier = inset.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.onboarding_done), style = MaterialTheme.typography.titleMedium)
    }
}
