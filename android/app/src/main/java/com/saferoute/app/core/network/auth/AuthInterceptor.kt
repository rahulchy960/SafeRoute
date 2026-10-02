// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.auth

import com.saferoute.app.core.network.ApiConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to requests that go to the SafeRoute API, when someone
 * is signed in. A request to any other server passes through untouched: the token would let
 * that server act as the user. The token is never logged.
 */
class AuthInterceptor(
    private val config: ApiConfig,
    private val tokens: IdTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!config.isApiRequest(request.url)) return chain.proceed(request)
        val token = tokens.idTokenBlocking(forceRefresh = false) ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header(AUTHORIZATION_HEADER, bearer(token)).build(),
        )
    }
}
