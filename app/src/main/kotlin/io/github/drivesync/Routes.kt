package io.github.drivesync

import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.feature.pairs.detail.PairDetailViewModel

/** Every screen of the app (PRD 8). */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD = "add"
    const val PAIR = "pair/{${PairDetailViewModel.PAIR_ID}}"
    const val FILES = "pair/{${PairDetailViewModel.PAIR_ID}}/files"
    const val CONFLICTS = "conflicts"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    fun pair(id: String) = "pair/$id"

    fun files(pairId: String) = "pair/$pairId/files"
}

/** The bottom navigation tabs. */
enum class Tab(
    val route: String,
    val label: Int,
    val icon: Int,
    val selectedIcon: Int,
) {
    FOLDERS(Routes.HOME, R.string.tab_folders, Sym.Folder, Sym.FolderFilled),
    CONFLICTS(Routes.CONFLICTS, R.string.tab_conflicts, Sym.Difference, Sym.DifferenceFilled),
    ACTIVITY(Routes.ACTIVITY, R.string.tab_activity, Sym.History, Sym.HistoryFilled),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, Sym.Settings, Sym.SettingsFilled),
}
