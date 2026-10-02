// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.retry

import com.saferoute.app.core.network.FIXED_CLOCK
import com.saferoute.app.core.network.HEALTH_JSON
import com.saferoute.app.core.network.RecordingSleeper
import com.saferoute.app.core.network.jsonResponse
import com.saferoute.app.core.network.problemResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** The retry rule on its own: which requests are repeated, how often and after how long. */
class RetryInterceptorTest {

    private val server = MockWebServer()
    private val sleeper = RecordingSleeper()

    /** Fails the first [failures] attempts with an IOException, as a dropped connection would. */
    private class Flaky(failures: Int) : Interceptor {
        val remaining = AtomicInteger(failures)
        val attempts = AtomicInteger()

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            attempts.incrementAndGet()
            if (remaining.getAndDecrement() > 0) throw IOException("connection dropped")
            return chain.proceed(chain.request())
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client(flaky: Flaky? = null, jitter: Long = 0): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RetryInterceptor(FIXED_CLOCK, sleeper) { jitter })
        .apply { flaky?.let { addInterceptor(it) } }
        .build()

    private fun OkHttpClient.send(method: String = "GET"): Int {
        val body = if (method == "GET" || method == "HEAD") null else "{}".toRequestBody()
        val request = Request.Builder().url(server.url("/v1/thing")).method(method, body).build()
        return newCall(request).execute().use { it.code }
    }

    private fun unavailable(retryAfter: String? = null): MockResponse =
        problemResponse(503, "db_unavailable").newBuilder()
            .apply { retryAfter?.let { addHeader("Retry-After", it) } }
            .build()

    @Test
    fun `GET is retried on 503 with 300 then 900 ms`() {
        server.enqueue(unavailable())
        server.enqueue(unavailable())
        server.enqueue(jsonResponse(HEALTH_JSON))

        assertEquals(200, client().send())
        assertEquals(3, server.requestCount)
        assertEquals(listOf(300L, 900L), sleeper.sleeps.toList())
    }

    @Test
    fun `GET gives up after two extra attempts and returns the last 503`() {
        repeat(4) { server.enqueue(unavailable()) }

        assertEquals(503, client().send())
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeper.sleeps.size)
    }

    @Test
    fun `jitter is added to the backoff`() {
        repeat(3) { server.enqueue(unavailable()) }

        client(jitter = 50).send()

        assertEquals(listOf(350L, 950L), sleeper.sleeps.toList())
    }

    @Test
    fun `Retry-After in seconds replaces the backoff and is capped at five seconds`() {
        server.enqueue(unavailable(retryAfter = "2"))
        server.enqueue(unavailable(retryAfter = "120"))
        server.enqueue(jsonResponse(HEALTH_JSON))

        assertEquals(200, client(jitter = 50).send())
        assertEquals(listOf(2_000L, 5_000L), sleeper.sleeps.toList())
    }

    @Test
    fun `Retry-After as a date is honoured and nonsense falls back to the backoff`() {
        // FIXED_CLOCK is 10:00:00; the date is three seconds later.
        server.enqueue(unavailable(retryAfter = "Sat, 03 Oct 2026 10:00:03 GMT"))
        server.enqueue(unavailable(retryAfter = "soon"))
        server.enqueue(jsonResponse(HEALTH_JSON))

        assertEquals(200, client().send())
        assertEquals(listOf(3_000L, 900L), sleeper.sleeps.toList())
    }

    @Test
    fun `GET is retried on a connection failure`() {
        val flaky = Flaky(failures = 2)
        server.enqueue(jsonResponse(HEALTH_JSON))

        assertEquals(200, client(flaky).send())
        assertEquals(3, flaky.attempts.get())
        assertEquals(listOf(300L, 900L), sleeper.sleeps.toList())
    }

    @Test
    fun `a connection failure that persists is thrown after two extra attempts`() {
        val flaky = Flaky(failures = 10)

        assertThrows(IOException::class.java) { client(flaky).send() }
        assertEquals(3, flaky.attempts.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `HEAD is retried like GET`() {
        // A HEAD response has no body.
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).build())

        assertEquals(200, client().send("HEAD"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `writes are never retried`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val before = server.requestCount
            server.enqueue(unavailable(retryAfter = "1"))

            assertEquals(503, client().send(method))
            assertEquals(1, server.requestCount - before)

            val flaky = Flaky(failures = 1)
            assertThrows(IOException::class.java) { client(flaky).send(method) }
            assertEquals(1, flaky.attempts.get())
        }
        assertEquals(emptyList<Long>(), sleeper.sleeps.toList())
    }

    @Test
    fun `other statuses are never retried`() {
        for (status in listOf(400, 401, 403, 404, 409, 429, 500, 502)) {
            val before = server.requestCount
            server.enqueue(problemResponse(status, "some_code"))

            assertEquals(status, client().send())
            assertEquals(1, server.requestCount - before)
        }
        assertEquals(emptyList<Long>(), sleeper.sleeps.toList())
    }
}
