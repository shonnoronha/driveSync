package io.github.drivesync

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.drivesync.feature.activity.ActivityRoute
import io.github.drivesync.feature.conflicts.ConflictsRoute
import io.github.drivesync.feature.pairs.add.AddPairRoute
import io.github.drivesync.feature.pairs.detail.PairDetailRoute
import io.github.drivesync.feature.pairs.detail.PairDetailViewModel
import io.github.drivesync.feature.pairs.home.HomeRoute
import io.github.drivesync.feature.settings.onboarding.OnboardingRoute
import io.github.drivesync.feature.settings.settings.SettingsRoute

@Composable
fun DriveSyncNavHost(
    nav: NavHostController,
    signedIn: Boolean,
) {
    NavHost(nav, startDestination = if (signedIn) Routes.HOME else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.HOME) {
            HomeRoute(
                onAddPair = { nav.navigate(Routes.ADD) },
                onOpenPair = { nav.navigate(Routes.pair(it)) },
                onOpenConflicts = { nav.navigate(Routes.CONFLICTS) },
                onOpenActivity = { nav.navigate(Routes.ACTIVITY) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ADD) {
            AddPairRoute(
                onClose = { nav.popBackStack() },
                onCreated = { id -> nav.navigate(Routes.pair(id)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.PAIR, listOf(navArgument(PairDetailViewModel.PAIR_ID) { type = NavType.StringType })) {
            PairDetailRoute(onBack = { nav.popBackStack() }, onOpenActivity = { nav.navigate(Routes.ACTIVITY) })
        }
        composable(Routes.CONFLICTS) { ConflictsRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.ACTIVITY) { ActivityRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { nav.popBackStack() },
                onSignedOut = { nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } } },
            )
        }
    }
}
