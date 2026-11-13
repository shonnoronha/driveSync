package io.github.drivesync.core.drive.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.drivesync.core.drive.api.DriveApi
import io.github.drivesync.core.drive.api.DriveClient
import io.github.drivesync.core.drive.auth.AuthProvider
import io.github.drivesync.core.drive.auth.google.GoogleAuth
import io.github.drivesync.core.drive.http.DriveHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveModule {
    @Binds
    abstract fun authProvider(impl: GoogleAuth): AuthProvider

    companion object {
        @Provides
        @Singleton
        fun driveApi(auth: AuthProvider): DriveApi = DriveHttp.api(auth)

        @Provides
        @Singleton
        fun driveClient(api: DriveApi): DriveClient = DriveClient(api)
    }
}
