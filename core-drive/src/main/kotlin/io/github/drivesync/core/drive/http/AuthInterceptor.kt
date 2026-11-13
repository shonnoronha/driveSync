package io.github.drivesync.core.drive.http

import io.github.drivesync.core.drive.auth.AuthProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Adds the access token to every request. Runs on OkHttp threads, so blocking on the provider is fine. */
class AuthInterceptor(
    private val auth: AuthProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { auth.accessToken() }
        return chain.proceed(chain.request().withToken(token))
    }
}

/**
 * On `401`: drop the rejected token, fetch a fresh one and retry once (PRD 6.3). A second `401` is returned to
 * the caller, which turns it into an [io.github.drivesync.core.drive.auth.AuthRequiredException].
 */
class TokenAuthenticator(
    private val auth: AuthProvider,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        if (response.priorResponse != null) return null
        val rejected = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)
        val fresh =
            runBlocking {
                rejected?.let { auth.invalidate(it) }
                auth.accessToken()
            }
        return response.request.withToken(fresh)
    }
}

private fun Request.withToken(token: String): Request = newBuilder().header(AUTHORIZATION, BEARER + token).build()
