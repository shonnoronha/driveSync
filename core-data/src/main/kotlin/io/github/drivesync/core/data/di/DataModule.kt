package io.github.drivesync.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.drivesync.core.data.db.DriveSyncDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): DriveSyncDatabase =
        Room
            .databaseBuilder(context, DriveSyncDatabase::class.java, DriveSyncDatabase.NAME)
            .addMigrations(
                DriveSyncDatabase.MIGRATION_1_2,
                DriveSyncDatabase.MIGRATION_2_3,
                DriveSyncDatabase.MIGRATION_3_4,
                DriveSyncDatabase.MIGRATION_4_5,
            ).build()

    @Provides
    @Singleton
    fun settingsStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}
