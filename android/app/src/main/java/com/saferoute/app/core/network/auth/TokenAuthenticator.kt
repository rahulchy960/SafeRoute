// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.auth

import com.saferoute.app.core.network.ApiConfig
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp calls an authenticator when a response is 401. Returning a request means "try this
 * instead"; returning null means "give up, hand the 401 to the caller".
 *
 * Rule (ADR 0006): refresh the token and retry **once**. It gives up when the failed request
 * carried no token, went to another server, was already a retry, or when the refresh yields no
 * token or the same token again. That makes a loop impossible.
 */
class TokenAuthenticator(
    private val config: ApiConfig,
    private val tokens: IdTokenProvider,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!config.isApiRequest(request.url)) return null
        val failedHeader = request.header(AUTHORIZATION_HEADER) ?: return null
        // priorResponse is set when this response already answers a retried request.
        if (response.priorResponse != null) return null

        val fresh = tokens.idTokenBlocking(forceRefresh = true) ?: return null
        if (bearer(fresh) == failedHeader) return null
        return request.newBuilder().header(AUTHORIZATION_HEADER, bearer(fresh)).build()
    }
}
