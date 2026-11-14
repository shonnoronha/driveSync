package io.github.drivesync.core.work.android

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.drivesync.core.data.pairs.SyncPair
import io.github.drivesync.core.sync.engine.SyncProgress
import io.github.drivesync.core.work.R
import io.github.drivesync.core.work.SyncNotifier
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channels and posts (FR-M3). Silently does nothing without the Android 13+ permission. */
@Singleton
class AndroidSyncNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SyncNotifier {
        private val manager = NotificationManagerCompat.from(context)

        init {
            manager.createNotificationChannelsCompat(
                listOf(
                    channel(CHANNEL_PROGRESS, R.string.channel_progress, NotificationManagerCompat.IMPORTANCE_LOW),
                    channel(
                        CHANNEL_ATTENTION,
                        R.string.channel_attention,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    ),
                    channel(
                        CHANNEL_CONFLICTS,
                        R.string.channel_conflicts,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    ),
                    channel(CHANNEL_ERRORS, R.string.channel_errors, NotificationManagerCompat.IMPORTANCE_LOW),
                ),
            )
        }

        /** The ongoing sync notification: which folder, and "3 / 182" with a bar once the work is known. */
        fun progress(
            pairName: String?,
            progress: SyncProgress?,
        ): Notification {
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_PROGRESS)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
            when {
                pairName == null -> {
                    builder.setContentTitle(context.getString(R.string.notification_syncing)).setProgress(0, 0, true)
                }

                progress is SyncProgress.Transferring && progress.total > 0 -> {
                    builder
                        .setContentTitle(context.getString(R.string.notification_syncing_pair, pairName))
                        .setContentText(
                            context.getString(R.string.notification_progress, progress.done, progress.total),
                        ).setProgress(progress.total, progress.done, false)
                }

                else -> {
                    builder
                        .setContentTitle(context.getString(R.string.notification_checking_pair, pairName))
                        .setProgress(0, 0, true)
                }
            }
            return builder.build()
        }

        override fun needsAttention(
            pair: SyncPair,
            detail: String,
        ) = post(
            ID_ATTENTION_BASE + pair.id.hashCode(),
            CHANNEL_ATTENTION,
            context.getString(R.string.notification_attention_title, pair.name),
            detail,
        )

        override fun conflicts(count: Int) =
            post(
                ID_CONFLICTS,
                CHANNEL_CONFLICTS,
                context.resources.getQuantityString(R.plurals.notification_conflicts, count, count),
                null,
            )

        override fun failures(
            pair: SyncPair,
            count: Int,
        ) = post(
            ID_ERRORS_BASE + pair.id.hashCode(),
            CHANNEL_ERRORS,
            context.resources.getQuantityString(R.plurals.notification_failures, count, count, pair.name),
            null,
        )

        private fun post(
            id: Int,
            channel: String,
            title: String,
            text: String?,
        ) {
            if (!canPost()) return
            val notification =
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(text?.let { NotificationCompat.BigTextStyle().bigText(it) })
                    .setAutoCancel(true)
                    .build()
            try {
                manager.notify(id, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the post.
            }
        }

        private fun canPost(): Boolean =
            manager.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

        private fun channel(
            id: String,
            name: Int,
            importance: Int,
        ) = NotificationChannelCompat.Builder(id, importance).setName(context.getString(name)).build()

        companion object {
            const val CHANNEL_PROGRESS = "sync_progress"
            const val CHANNEL_ATTENTION = "needs_attention"
            const val CHANNEL_CONFLICTS = "conflicts"
            const val CHANNEL_ERRORS = "errors"
            const val ID_PROGRESS = 1
            const val ID_CONFLICTS = 2
            private const val ID_ATTENTION_BASE = 1_000
            private const val ID_ERRORS_BASE = 2_000_000
        }
    }
