package io.github.drivesync

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Page transitions in Android's "fade forwards" style: the new page slides in a little while it
 * fades in, the old one drifts the other way and fades out, and back reverses it. The predictive back gesture
 * scrubs the same motion, instead of shrinking the page into a card. Switching tabs only fades.
 */
internal object Transitions {
    private const val DURATION_MS = 350
    private const val FADE_IN_MS = 210
    private const val FADE_OUT_MS = 90
    private const val SHIFT_DIVISOR = 10

    /** Material "emphasized decelerate" easing. */
    private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    private val tabRoutes = Tab.entries.map { it.route }.toSet()

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs(): Boolean =
        initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

    private fun fadeInPage() = fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS))

    private fun fadeOutPage() = fadeOut(tween(FADE_OUT_MS))

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) {
            fadeInPage()
        } else {
            slideInHorizontally(tween(DURATION_MS, easing = Emphasized)) { it / SHIFT_DIVISOR } + fadeInPage()
        }
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) {
            fadeOutPage()
        } else {
            slideOutHorizontally(tween(DURATION_MS, easing = Emphasized)) { -it / SHIFT_DIVISOR } + fadeOutPage()
        }
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) {
            fadeInPage()
        } else {
            slideInHorizontally(tween(DURATION_MS, easing = Emphasized)) { -it / SHIFT_DIVISOR } + fadeInPage()
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) {
            fadeOutPage()
        } else {
            slideOutHorizontally(tween(DURATION_MS, easing = Emphasized)) { it / SHIFT_DIVISOR } + fadeOutPage()
        }
    }

    /**
     * The back swipe uses its own transitions, which Navigation defaults to shrinking the page into a card (the
     * "screenshot" look). Here both pages stay visible instead: an even crossfade across the whole gesture with the
     * same small shift as the back button. The swipe edge does not matter.
     */
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        slideInHorizontally(tween(DURATION_MS)) { -it / SHIFT_DIVISOR } + fadeIn(tween(DURATION_MS))
    }

    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = {
        slideOutHorizontally(tween(DURATION_MS)) { it / SHIFT_DIVISOR } + fadeOut(tween(DURATION_MS))
    }
}
