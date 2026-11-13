package io.github.drivesync

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.feature.activity.ActivityRoute
import io.github.drivesync.feature.conflicts.ConflictsRoute
import io.github.drivesync.feature.pairs.add.AddPairRoute
import io.github.drivesync.feature.pairs.detail.PairDetailRoute
import io.github.drivesync.feature.pairs.detail.PairDetailViewModel
import io.github.drivesync.feature.pairs.home.HomeRoute
import io.github.drivesync.feature.settings.onboarding.OnboardingRoute
import io.github.drivesync.feature.settings.settings.SettingsRoute

/** The app shell: a bottom navigation bar on the tab roots, full-screen sub-pages on top (PRD 8). */
@Composable
fun DriveSyncNavHost(
    nav: NavHostController,
    signedIn: Boolean,
    pendingConflicts: Int,
) {
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (Tab.entries.any { it.route == currentRoute }) {
                BottomBar(currentRoute, pendingConflicts) { nav.switchTab(it) }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = if (signedIn) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = Transitions.enter,
            exitTransition = Transitions.exit,
            popEnterTransition = Transitions.popEnter,
            popExitTransition = Transitions.popExit,
            predictivePopEnterTransition = Transitions.predictivePopEnter,
            predictivePopExitTransition = Transitions.predictivePopExit,
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingRoute(
                    onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                )
            }
            composable(Routes.HOME) {
                HomeRoute(onAddPair = { nav.navigate(Routes.ADD) }, onOpenPair = { nav.navigate(Routes.pair(it)) })
            }
            composable(Routes.ADD) {
                AddPairRoute(
                    onClose = { nav.popBackStack() },
                    onCreated = { id -> nav.navigate(Routes.pair(id)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.PAIR, listOf(navArgument(PairDetailViewModel.PAIR_ID) { type = NavType.StringType })) {
                PairDetailRoute(onBack = { nav.popBackStack() }, onOpenActivity = { nav.switchTab(Tab.ACTIVITY) })
            }
            composable(Routes.CONFLICTS) { ConflictsRoute() }
            composable(Routes.ACTIVITY) { ActivityRoute() }
            composable(Routes.SETTINGS) {
                SettingsRoute(onSignedOut = { nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomBar(
    currentRoute: String?,
    pendingConflicts: Int,
    onSelect: (Tab) -> Unit,
) {
    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { tab ->
            val selected = tab.route == currentRoute
            ShortNavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = {
                        if (tab == Tab.CONFLICTS &&
                            pendingConflicts > 0
                        ) {
                            Badge { Text("$pendingConflicts") }
                        }
                    }) {
                        SymIcon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null)
                    }
                },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

/** Standard tab switching: one copy of each tab, state kept, back goes to Folders. */
private fun NavHostController.switchTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
