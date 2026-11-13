package io.github.drivesync.core.drive.http

import io.github.drivesync.core.drive.api.DriveApi
import io.github.drivesync.core.drive.auth.AuthProvider
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Builds the Drive HTTP stack: auth → retry → network. */
object DriveHttp {
    val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private const val TIMEOUT_SECONDS = 60L

    fun api(
        auth: AuthProvider,
        baseUrl: String = DriveApi.BASE_URL,
        retry: RetryInterceptor = RetryInterceptor(),
    ): DriveApi {
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor(retry)
                .addInterceptor(AuthInterceptor(auth))
                .authenticator(TokenAuthenticator(auth))
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        return Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
    }
}
