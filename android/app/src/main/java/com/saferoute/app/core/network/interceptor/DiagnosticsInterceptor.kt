// SPDX-License-Identifier: AGPL-3.0-only

package com.saferoute.app.core.network.interceptor

import com.saferoute.app.core.diagnostics.ApiErrorKind
import com.saferoute.app.core.diagnostics.DiagnosticEvent
import com.saferoute.app.core.diagnostics.DiagnosticsReporter
import okhttp3.Interceptor
import okhttp3.Response

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_UNAVAILABLE = 503

/**
 * Notes that the server failed, as a kind and nothing else (ADR 0028): no address, no
 * status text, no request id, no body. It sits outside the retry interceptor, so it sees
 * the answer the app ends up with, once.
 *
 * Not noted on purpose: a missing connection (ordinary on a phone, and it would fill the
 * short list with one line), and answers such as "not found" or "already exists", which
 * are results, not failures.
 */
class DiagnosticsInterceptor(private val reporter: DiagnosticsReporter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        apiErrorKind(response.code)?.let { reporter.report(DiagnosticEvent.ApiError(it)) }
        return response
    }
}

internal fun apiErrorKind(status: Int): ApiErrorKind? = when {
    status == HTTP_TOO_MANY_REQUESTS -> ApiErrorKind.RATE_LIMITED
    status == HTTP_UNAVAILABLE -> ApiErrorKind.UNAVAILABLE
    status >= HTTP_SERVER_ERROR -> ApiErrorKind.SERVER_ERROR
    else -> null
}
