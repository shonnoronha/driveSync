package io.github.drivesync.core.work.android

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.drivesync.core.work.SyncEnvironment
import io.github.drivesync.core.work.SyncNotifier
import io.github.drivesync.core.work.SyncStores

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {
    @Binds
    abstract fun stores(impl: AndroidSyncStores): SyncStores

    @Binds
    abstract fun environment(impl: AndroidSyncEnvironment): SyncEnvironment

    @Binds
    abstract fun notifier(impl: AndroidSyncNotifier): SyncNotifier

    companion object {
        @Provides
        fun workManager(
            @ApplicationContext context: Context,
        ): WorkManager = WorkManager.getInstance(context)
    }
}
