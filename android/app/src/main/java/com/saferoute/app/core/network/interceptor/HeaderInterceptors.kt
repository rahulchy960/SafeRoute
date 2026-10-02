// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.interceptor

import java.util.UUID
import okhttp3.Interceptor
import okhttp3.Response

/*
 * An interceptor sees every request before it is sent and every response before the caller
 * gets it, and may change either. OkHttp runs them in the order they were added.
 */

const val REQUEST_ID_HEADER = "X-Request-Id"

/**
 * Gives every request an `X-Request-Id` (a new random UUID) unless it already has one. The
 * backend echoes it and writes it into its logs, so one failing call can be found there.
 */
class RequestIdInterceptor(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(REQUEST_ID_HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(REQUEST_ID_HEADER, newId()).build())
    }
}

/** Sends `User-Agent: SafeRoute-Android/<version>` and nothing that identifies the device. */
class UserAgentInterceptor(versionName: String) : Interceptor {

    private val userAgent = "SafeRoute-Android/$versionName"

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}
