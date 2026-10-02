// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.isRetryable
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The debug server check through the app's real client against a local test server. */
class ApiServerCheckTest {

    private val server = MockWebServer()
    private lateinit var api: TestApi
    private lateinit var check: ApiServerCheck

    @Before
    fun setUp() {
        server.start()
        api = TestApi(server)
        check = ApiServerCheck(api.config, api.create())
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `health reports the backend version and the request id`() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))

        assertEquals(ProbeResult.Up("0.1.0", TEST_REQUEST_ID), check.health())
        val request = server.takeRequest()
        assertEquals("/health", request.url.encodedPath)
        assertTrue(request.headers["X-Request-Id"].orEmpty().isNotEmpty())
    }

    @Test
    fun `readiness reports ready`() = runTest {
        server.enqueue(jsonResponse(READINESS_JSON))

        assertEquals(ProbeResult.Up(null, TEST_REQUEST_ID), check.readiness())
        assertEquals("/health/ready", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a 503 readiness after the retries is a retryable problem with its request id`() = runTest {
        repeat(3) { server.enqueue(problemResponse(503, "db_unavailable")) }

        val result = check.readiness() as ProbeResult.Down

        assertEquals("db_unavailable", (result.failure as ApiFailure.Problem).code)
        assertTrue(isRetryable(result.failure))
        assertEquals(TEST_REQUEST_ID, result.requestId)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an unreachable server is no connection`() = runTest {
        server.close()

        assertEquals(ProbeResult.Down(ApiFailure.NoConnection), check.health())
    }

    @Test
    fun `the configured flag comes from the build config`() {
        assertTrue(check.isConfigured)
        val placeholder = ApiServerCheck(api.config.copy(isConfigured = false), api.create())
        assertFalse(placeholder.isConfigured)
    }
}
