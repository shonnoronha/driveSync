package io.github.drivesync.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.github.drivesync.core.ui.R

/** Material Symbols Rounded used by the app (vector drawables, see docs/licenses). */
object Sym {
    val Add = R.drawable.sym_add
    val ArrowBack = R.drawable.sym_arrow_back
    val ArrowForward = R.drawable.sym_arrow_forward
    val ChevronRight = R.drawable.sym_chevron_right
    val Close = R.drawable.sym_close
    val Check = R.drawable.sym_check
    val CheckCircle = R.drawable.sym_check_circle
    val Sync = R.drawable.sym_sync
    val SyncAlt = R.drawable.sym_sync_alt
    val SyncProblem = R.drawable.sym_sync_problem
    val CloudUpload = R.drawable.sym_cloud_upload
    val CloudDownload = R.drawable.sym_cloud_download
    val CloudSync = R.drawable.sym_cloud_sync
    val CloudDone = R.drawable.sym_cloud_done
    val CloudOff = R.drawable.sym_cloud_off
    val Folder = R.drawable.sym_folder
    val FolderFilled = R.drawable.sym_folder_fill
    val FolderOpen = R.drawable.sym_folder_open
    val CreateNewFolder = R.drawable.sym_create_new_folder
    val History = R.drawable.sym_history
    val HistoryFilled = R.drawable.sym_history_fill
    val Settings = R.drawable.sym_settings
    val SettingsFilled = R.drawable.sym_settings_fill
    val Difference = R.drawable.sym_difference
    val DifferenceFilled = R.drawable.sym_difference_fill
    val Delete = R.drawable.sym_delete
    val RestoreFromTrash = R.drawable.sym_restore_from_trash
    val DriveFileMove = R.drawable.sym_drive_file_move
    val Error = R.drawable.sym_error
    val Info = R.drawable.sym_info
    val Warning = R.drawable.sym_warning
    val PauseCircle = R.drawable.sym_pause_circle
    val Notifications = R.drawable.sym_notifications
    val Battery = R.drawable.sym_battery_charging_full
    val Lock = R.drawable.sym_lock
    val Palette = R.drawable.sym_palette
    val Schedule = R.drawable.sym_schedule
    val Wifi = R.drawable.sym_wifi
    val Bolt = R.drawable.sym_bolt
    val Smartphone = R.drawable.sym_smartphone
    val Article = R.drawable.sym_article
    val Code = R.drawable.sym_code
    val BugReport = R.drawable.sym_bug_report
    val Logout = R.drawable.sym_logout
    val DarkMode = R.drawable.sym_dark_mode
    val LightMode = R.drawable.sym_light_mode
    val Contrast = R.drawable.sym_contrast
    val BrightnessAuto = R.drawable.sym_brightness_auto
    val AccountCircle = R.drawable.sym_account_circle
    val SdCard = R.drawable.sym_sd_card
    val ExpandMore = R.drawable.sym_expand_more
    val Block = R.drawable.sym_block
    val ColorFill = R.drawable.sym_format_color_fill
}

@Composable
fun SymIcon(
    @DrawableRes symbol: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(symbol), contentDescription, modifier, tint)
}
