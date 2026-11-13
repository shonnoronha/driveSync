package io.github.drivesync

import io.github.drivesync.feature.pairs.detail.PairDetailViewModel

/** Every screen of the app (PRD 8). */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD = "add"
    const val PAIR = "pair/{${PairDetailViewModel.PAIR_ID}}"
    const val CONFLICTS = "conflicts"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    fun pair(id: String) = "pair/$id"
}
