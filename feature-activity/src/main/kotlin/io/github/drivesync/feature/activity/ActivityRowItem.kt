package io.github.drivesync.feature.activity

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.data.db.ActivityKind
import java.util.Date

/** One activity line; also used by the pair detail screen. */
@Composable
fun ActivityRowItem(
    row: ActivityRow,
    showPair: Boolean,
    modifier: Modifier = Modifier,
) {
    val problem = row.kind in setOf(ActivityKind.FAILED, ActivityKind.PAUSED, ActivityKind.WAITING_FOR_CONFIRMATION)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            row.kind.icon(),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.path ?: row.kind.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle =
                listOfNotNull(
                    row.kind.label().takeIf { row.path != null },
                    row.detail,
                    row.pairName.takeIf { showPair },
                )
            Text(
                subtitle.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            DateFormat.getTimeFormat(LocalContext.current).format(Date(row.time)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ActivityKind.label(): String =
    stringResource(
        when (this) {
            ActivityKind.UPLOADED -> R.string.kind_uploaded
            ActivityKind.DOWNLOADED -> R.string.kind_downloaded
            ActivityKind.MOVED -> R.string.kind_moved
            ActivityKind.FOLDER_CREATED -> R.string.kind_folder_created
            ActivityKind.TRASHED_ON_DRIVE -> R.string.kind_trashed
            ActivityKind.RECYCLED_ON_PHONE -> R.string.kind_recycled
            ActivityKind.CONFLICT -> R.string.kind_conflict
            ActivityKind.FAILED -> R.string.kind_failed
            ActivityKind.PAUSED -> R.string.kind_paused
            ActivityKind.WAITING_FOR_CONFIRMATION -> R.string.kind_confirm
        },
    )

private fun ActivityKind.icon(): ImageVector =
    when (this) {
        ActivityKind.UPLOADED -> Icons.Outlined.CloudUpload
        ActivityKind.DOWNLOADED -> Icons.Outlined.CloudDownload
        ActivityKind.MOVED -> Icons.AutoMirrored.Outlined.DriveFileMove
        ActivityKind.FOLDER_CREATED -> Icons.Outlined.CreateNewFolder
        ActivityKind.TRASHED_ON_DRIVE -> Icons.Outlined.Delete
        ActivityKind.RECYCLED_ON_PHONE -> Icons.Outlined.RestoreFromTrash
        ActivityKind.CONFLICT -> Icons.Outlined.Difference
        ActivityKind.FAILED -> Icons.Outlined.ErrorOutline
        ActivityKind.PAUSED -> Icons.Outlined.PauseCircle
        ActivityKind.WAITING_FOR_CONFIRMATION -> Icons.Outlined.Warning
    }
