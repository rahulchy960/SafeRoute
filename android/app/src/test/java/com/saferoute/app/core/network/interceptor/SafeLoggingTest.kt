// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.interceptor

import com.saferoute.app.core.network.FakeIdTokenProvider
import com.saferoute.app.core.network.ME_JSON
import com.saferoute.app.core.network.TestApi
import com.saferoute.app.core.network.jsonResponse
import java.io.IOException
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** With the debug logger on, nothing sensitive reaches the log. */
class SafeLoggingTest {

    private val server = MockWebServer()
    private val secrets = listOf(
        "token-secret", "refreshed-secret", "Authorization", "Bearer", "Cookie",
        "session-secret", "query-secret", "phone", "request-body-secret", "Sample Name",
        "+910000000001", "?",
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun debugApi() = TestApi(
        server,
        FakeIdTokenProvider(token = "token-secret", refreshed = "refreshed-secret"),
        debugLogging = true,
    )

    private fun assertNoSecrets(lines: List<String>) {
        for (line in lines) {
            for (secret in secrets) {
                assertFalse("log line leaks '$secret'", line.contains(secret, ignoreCase = true))
            }
            assertFalse("log line leaks the host", line.contains(server.hostName))
            assertFalse("log line leaks the port", line.contains(server.port.toString()))
        }
    }

    @Test
    fun `a request with a token, a query string, cookies and bodies logs none of them`() {
        val api = debugApi()
        server.enqueue(
            jsonResponse(ME_JSON).newBuilder()
                .addHeader("Set-Cookie", "session=session-secret")
                .build(),
        )
        val request = Request.Builder()
            .url(server.url("/v1/me/bootstrap?phone=%2B910000000001&q=query-secret"))
            .header("Cookie", "session=session-secret")
            .header("X-Request-Id", "logged-request-id")
            .post("""{"displayName":"request-body-secret"}""".toRequestBody())
            .build()

        api.client.newCall(request).execute().use { it.body.string() }

        val lines = api.log.lines.toList()
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("POST /v1/me/bootstrap -> 200 in "))
        assertTrue(lines[0].endsWith(" ms id=logged-request-id"))
        assertNoSecrets(lines)
        // The request really carried what the log must not show.
        assertEquals("Bearer token-secret", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a failed attempt logs the kind of failure only`() {
        val api = debugApi()
        server.close()
        val request = Request.Builder().url(api.config.baseUrl.resolve("/v1/me?q=query-secret")!!)
            .post("request-body-secret".toRequestBody())
            .build()

        assertThrows(IOException::class.java) { api.client.newCall(request).execute() }

        val lines = api.log.lines.toList()
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("POST /v1/me -> failed ("))
        assertNoSecrets(lines)
    }

    @Test
    fun `every retry attempt is logged`() {
        val api = debugApi()
        repeat(2) { server.enqueue(jsonResponse("{}", code = 503)) }
        server.enqueue(jsonResponse(ME_JSON))

        api.client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()

        val statuses = api.log.lines.map { it.substringAfter("-> ").substringBefore(" in") }
        assertEquals(listOf("503", "503", "200"), statuses)
        assertNoSecrets(api.log.lines.toList())
    }

    @Test
    fun `nothing is logged when debug logging is off, as in a release build`() {
        val api = TestApi(server, FakeIdTokenProvider(token = "token-secret"), debugLogging = false)
        server.enqueue(jsonResponse(ME_JSON))

        api.client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()

        assertEquals(emptyList<String>(), api.log.lines.toList())
        assertTrue(api.client.interceptors.none { it is SafeLoggingInterceptor })
    }
}
