// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.interceptor

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/** Where the debug log lines go. Tests pass their own to read the lines. */
fun interface NetworkLog {
    fun log(line: String)
}

/** Writes to Logcat (Android's log) under the tag `SafeRouteHttp`. */
object LogcatNetworkLog : NetworkLog {
    override fun log(line: String) {
        Log.d("SafeRouteHttp", line)
    }
}

/**
 * Debug builds only: one line per attempt with the method, the path, the status, the duration
 * and the request id, for example `GET /v1/me -> 200 in 84 ms id=3f2c...`.
 *
 * It never prints the host, the query string, any header (so no `Authorization`, `Cookie` or
 * `Set-Cookie`) or any body, and for a failed attempt only the exception's class name, because
 * its message can contain the host. Release builds do not install it.
 */
class SafeLoggingInterceptor(private val log: NetworkLog) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${request.url.encodedPath}"
        val id = request.header(REQUEST_ID_HEADER) ?: "-"
        val start = System.nanoTime()
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log.log("$what -> failed (${e.javaClass.simpleName}) in ${elapsed()} ms id=$id")
            throw e
        }
        log.log("$what -> ${response.code} in ${elapsed()} ms id=$id")
        return response
    }
}
