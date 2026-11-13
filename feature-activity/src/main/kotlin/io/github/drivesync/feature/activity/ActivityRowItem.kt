package io.github.drivesync.feature.activity

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drivesync.core.data.db.ActivityKind
import io.github.drivesync.core.ui.components.Sym
import io.github.drivesync.core.ui.components.SymIcon
import io.github.drivesync.core.ui.theme.Tokens
import java.util.Date

/** One activity line, sized for a connected group row; also used by pair detail. */
@Composable
fun ActivityRowItem(
    row: ActivityRow,
    showPair: Boolean,
    modifier: Modifier = Modifier,
) {
    val problem = row.kind in setOf(ActivityKind.FAILED, ActivityKind.PAUSED, ActivityKind.WAITING_FOR_CONFIRMATION)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = Tokens.gapLg, vertical = Tokens.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (problem) scheme.errorContainer else scheme.secondaryContainer,
            contentColor = if (problem) scheme.onErrorContainer else scheme.onSecondaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                SymIcon(row.kind.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(Tokens.gapMd))
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
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Tokens.gapSm))
        Text(
            DateFormat.getTimeFormat(LocalContext.current).format(Date(row.time)),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
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

private fun ActivityKind.icon(): Int =
    when (this) {
        ActivityKind.UPLOADED -> Sym.CloudUpload
        ActivityKind.DOWNLOADED -> Sym.CloudDownload
        ActivityKind.MOVED -> Sym.DriveFileMove
        ActivityKind.FOLDER_CREATED -> Sym.CreateNewFolder
        ActivityKind.TRASHED_ON_DRIVE -> Sym.Delete
        ActivityKind.RECYCLED_ON_PHONE -> Sym.RestoreFromTrash
        ActivityKind.CONFLICT -> Sym.Difference
        ActivityKind.FAILED -> Sym.Error
        ActivityKind.PAUSED -> Sym.PauseCircle
        ActivityKind.WAITING_FOR_CONFIRMATION -> Sym.Warning
    }
