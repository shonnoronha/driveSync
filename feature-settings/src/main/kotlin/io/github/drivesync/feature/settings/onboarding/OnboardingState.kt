package io.github.drivesync.feature.settings.onboarding

enum class OnboardingStep { WELCOME, WORKING, PERMISSIONS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    /** True while the Drive Sync folder is being found or created (after sign-in). */
    val settingUpDrive: Boolean = false,
    val accountEmail: String? = null,
    val notificationsAllowed: Boolean = false,
    val error: String? = null,
)

/** Callbacks of the onboarding screen. */
data class OnboardingActions(
    val onSignIn: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onDone: () -> Unit = {},
)
