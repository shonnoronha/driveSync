package io.github.drivesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.drivesync.core.data.settings.ThemeMode
import io.github.drivesync.core.ui.theme.DriveSyncTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()
            val loaded = settings ?: return@setContent // Splash shows until settings load (milliseconds).
            val dark =
                when (loaded.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            DriveSyncTheme(darkTheme = dark, dynamicColor = loaded.dynamicColor) {
                DriveSyncNavHost(rememberNavController(), signedIn = loaded.accountEmail != null)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppVisible()
    }
}
