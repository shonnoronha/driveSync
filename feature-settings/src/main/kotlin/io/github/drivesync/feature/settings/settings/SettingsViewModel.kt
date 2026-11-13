package io.github.drivesync.feature.settings.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drivesync.core.data.AppDataCleaner
import io.github.drivesync.core.data.pairs.PairRepository
import io.github.drivesync.core.data.settings.SettingsRepository
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.core.drive.auth.google.GoogleAccount
import io.github.drivesync.core.drive.auth.google.GoogleAuth
import io.github.drivesync.core.localstorage.saf.SafTrees
import io.github.drivesync.core.work.android.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val pairs: PairRepository,
        private val trees: SafTrees,
        private val scheduler: SyncScheduler,
        private val auth: GoogleAuth,
        private val cleaner: AppDataCleaner,
    ) : ViewModel() {
        private val signedOut = MutableStateFlow(false)

        val state: StateFlow<SettingsUiState> =
            combine(settings.settings, signedOut) { app, out ->
                SettingsUiState(
                    loading = false,
                    settings = app,
                    defaultDeviceName = Build.MODEL ?: "Android",
                    version =
                        context.packageManager
                            .getPackageInfo(context.packageName, 0)
                            .versionName
                            .orEmpty(),
                    signedOut = out,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

        fun setInterval(minutes: Int) = launch { settings.setSyncInterval(minutes) }

        fun setNetworkRules(
            wifiOnly: Boolean,
            chargingOnly: Boolean,
        ) = launch { settings.setNetworkRules(wifiOnly, chargingOnly) }

        fun setDeviceName(name: String) = launch { settings.setDeviceName(name) }

        fun setTheme(
            theme: ThemeMode,
            dynamicColor: Boolean,
        ) = launch { settings.setTheme(theme, dynamicColor) }

        fun setLogRetention(days: Int) = launch { settings.setLogRetentionDays(days) }

        /** FR-A5: stop syncing, give back folder grants, revoke Drive access, delete app data. Files stay. */
        fun signOut() =
            launch {
                scheduler.cancelAll()
                pairs.pairs().forEach { runCatching { trees.release(it.localTreeUri) } }
                settings.current().accountEmail?.let { email ->
                    try {
                        auth.signOut(GoogleAccount(email, null))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Offline or already revoked: local data is still deleted below.
                    }
                }
                cleaner.clearAll()
                signedOut.value = true
            }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch { block() }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    fun open(intent: Intent) = context.startActivity(intent)
    SettingsScreen(
        state = state,
        actions =
            SettingsActions(
                onBack = onBack,
                onSignOut = viewModel::signOut,
                onInterval = viewModel::setInterval,
                onNetworkRules = viewModel::setNetworkRules,
                onDeviceName = viewModel::setDeviceName,
                onTheme = viewModel::setTheme,
                onLogRetention = viewModel::setLogRetention,
                onOpenNotifications = {
                    open(
                        Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                onOpenBattery = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                onOpenSource = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsChoices.SOURCE_URL))) },
                onReportProblem = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsChoices.ISSUES_URL))) },
            ),
    )
}
