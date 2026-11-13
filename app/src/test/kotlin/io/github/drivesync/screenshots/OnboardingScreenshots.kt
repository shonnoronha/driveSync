package io.github.drivesync.screenshots

import io.github.drivesync.feature.settings.onboarding.OnboardingActions
import io.github.drivesync.feature.settings.onboarding.OnboardingScreen
import io.github.drivesync.feature.settings.onboarding.OnboardingStep
import io.github.drivesync.feature.settings.onboarding.OnboardingUiState
import org.junit.Test

class OnboardingScreenshots : Screenshots() {
    @Test
    fun welcome() = snap("onboarding_1_welcome") { OnboardingScreen(OnboardingUiState(), OnboardingActions()) }

    @Test
    fun working() =
        snap("onboarding_2_working") {
            OnboardingScreen(
                OnboardingUiState(step = OnboardingStep.WORKING, settingUpDrive = true),
                OnboardingActions(),
            )
        }

    @Test
    fun permissions() =
        snap("onboarding_3_permissions", dark = true) {
            OnboardingScreen(
                OnboardingUiState(step = OnboardingStep.PERMISSIONS, accountEmail = "you@gmail.com"),
                OnboardingActions(),
            )
        }
}
