// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.retry

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import okhttp3.Interceptor
import okhttp3.Response

/** Waits between attempts. Tests pass their own so that no real time goes by. */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)
}

/**
 * Repeats a request that failed for a reason that may pass: a 503 or a connection problem.
 *
 * Only `GET` and `HEAD` are repeated. They are idempotent: sending one twice changes nothing on
 * the server. A `POST` is never repeated here, because the first one may have arrived and a
 * second would do the work twice (create two SOS sessions, say). Writes get their own policy
 * with an `Idempotency-Key` in the prompts that add them.
 *
 * Up to two extra attempts, after about 300 ms and 900 ms (plus a little random "jitter", so
 * that many phones don't retry in the same instant). A `Retry-After` header on a 503 replaces
 * the wait, capped at five seconds. 401 is not handled here: that is the authenticator's job.
 */
class RetryInterceptor(
    private val clock: Clock,
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
    private val jitterMillis: () -> Long = { Random.nextLong(MAX_JITTER_MILLIS + 1) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in IDEMPOTENT_METHODS) return chain.proceed(request)

        var attempt = 0
        while (true) {
            val wait = try {
                val response = chain.proceed(request)
                if (response.code != HTTP_UNAVAILABLE || attempt == BACKOFF_MILLIS.size) {
                    return response
                }
                // A response must be closed before the same call is sent again.
                response.close()
                retryAfterMillis(response) ?: backoff(attempt)
            } catch (e: IOException) {
                if (chain.call().isCanceled() || attempt == BACKOFF_MILLIS.size) throw e
                backoff(attempt)
            }
            pause(wait)
            attempt++
        }
    }

    private fun backoff(attempt: Int): Long = BACKOFF_MILLIS[attempt] + jitterMillis()

    private fun pause(millis: Long) {
        try {
            sleeper.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting to retry")
        }
    }

    /** `Retry-After` is either a number of seconds or an HTTP date. Anything else is ignored. */
    private fun retryAfterMillis(response: Response): Long? {
        val value = response.header("Retry-After")?.trim() ?: return null
        val millis = value.toLongOrNull()?.let { Duration.ofSeconds(it.coerceIn(0, 60)).toMillis() }
            ?: runCatching {
                val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                Duration.between(clock.instant(), at.toInstant()).toMillis()
            }.getOrNull()
            ?: return null
        return millis.coerceIn(0, MAX_RETRY_AFTER_MILLIS)
    }

    companion object {
        const val MAX_RETRY_AFTER_MILLIS = 5_000L
        const val MAX_JITTER_MILLIS = 100L
        val BACKOFF_MILLIS = listOf(300L, 900L)
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD")
        private const val HTTP_UNAVAILABLE = 503
    }
}
