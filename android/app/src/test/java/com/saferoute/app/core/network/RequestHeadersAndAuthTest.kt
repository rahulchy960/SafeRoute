// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.network.di.newApiHttpClient
import com.saferoute.app.core.network.di.newApiRetrofit
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Request id, user agent, where the token goes, and the one-refresh rule for 401. */
class RequestHeadersAndAuthTest {

    private val server = MockWebServer()
    private val otherServer = MockWebServer()
    private val requestIdRule = Regex("^[A-Za-z0-9._-]{8,64}$")

    @Before
    fun setUp() {
        server.start()
        otherServer.start()
    }

    @After
    fun tearDown() {
        server.close()
        otherServer.close()
    }

    private fun TestApi.get(url: HttpUrl, requestId: String? = null): Int {
        val request = Request.Builder().url(url)
            .apply { requestId?.let { header("X-Request-Id", it) } }
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    @Test
    fun `every request has its own well-formed request id and the user agent`() = runTest {
        val api = TestApi(server)
        server.enqueue(jsonResponse(ME_JSON))
        server.enqueue(jsonResponse(ME_JSON))

        apiCall { api.create<MeApi>().getMe() }
        apiCall { api.create<MeApi>().getMe() }

        val first = server.takeRequest()
        val second = server.takeRequest()
        val firstId = first.headers["X-Request-Id"].orEmpty()
        val secondId = second.headers["X-Request-Id"].orEmpty()
        assertTrue(requestIdRule.matches(firstId))
        assertTrue(requestIdRule.matches(secondId))
        assertNotEquals(firstId, secondId)
        assertEquals("SafeRoute-Android/$TEST_VERSION", first.headers["User-Agent"])
    }

    @Test
    fun `a request id set by the caller is kept`() {
        val api = TestApi(server)
        server.enqueue(jsonResponse(HEALTH_JSON))

        api.get(server.url("/health"), requestId = "caller-chosen-id")

        assertEquals("caller-chosen-id", server.takeRequest().headers["X-Request-Id"])
    }

    @Test
    fun `no Authorization header when nobody is signed in`() = runTest {
        val api = TestApi(server)
        server.enqueue(jsonResponse(ME_JSON))

        apiCall { api.create<MeApi>().getMe() }

        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals(listOf(false), api.tokens.calls.toList())
    }

    @Test
    fun `the token is sent to the API`() = runTest {
        val api = TestApi(server, FakeIdTokenProvider(token = "token-one"))
        server.enqueue(jsonResponse(ME_JSON))

        apiCall { api.create<MeApi>().getMe() }

        assertEquals("Bearer token-one", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `the token is never sent to another server`() {
        // The second server differs from the API only by its port: still not the API.
        val api = TestApi(server, FakeIdTokenProvider(token = "token-one", refreshed = "token-two"))
        otherServer.enqueue(jsonResponse("{}"))
        otherServer.enqueue(jsonResponse("{}", code = 401))

        api.get(otherServer.url("/tiles/1/2/3.png"))
        val status = api.get(otherServer.url("/tiles/private"))

        assertNull(otherServer.takeRequest().headers["Authorization"])
        assertNull(otherServer.takeRequest().headers["Authorization"])
        assertEquals(401, status)
        // No token was even asked for, and the 401 from the other server triggered no refresh.
        assertEquals(emptyList<Boolean>(), api.tokens.calls.toList())
        assertEquals(2, otherServer.requestCount)
    }

    @Test
    fun `401 with a token refreshes once and retries with the new token`() = runTest {
        val api = TestApi(server, FakeIdTokenProvider(token = "old", refreshed = "new"))
        server.enqueue(problemResponse(401, "unauthorized"))
        server.enqueue(jsonResponse(ME_JSON))

        val result = apiCall { api.create<MeApi>().getMe() }

        assertTrue(result is ApiResult.Success)
        assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
        assertEquals(listOf(false, true), api.tokens.calls.toList())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a POST is also retried once after 401`() = runTest {
        val api = TestApi(server, FakeIdTokenProvider(token = "old", refreshed = "new"))
        server.enqueue(problemResponse(401, "unauthorized"))
        server.enqueue(jsonResponse(ME_JSON, code = 201))

        val result = apiCall { api.create<MeApi>().bootstrapMe(BootstrapMeRequest()) }

        assertEquals(201, (result as ApiResult.Success).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a second 401 gives up without a loop`() = runTest {
        val api = TestApi(server, FakeIdTokenProvider(token = "old", refreshed = "new"))
        repeat(3) { server.enqueue(problemResponse(401, "unauthorized")) }

        val result = apiCall { api.create<MeApi>().getMe() }

        assertEquals(ApiResult.Failure(ApiFailure.Unauthorized(TEST_REQUEST_ID)), result)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(false, true), api.tokens.calls.toList())
    }

    @Test
    fun `401 without a token does not refresh`() = runTest {
        val api = TestApi(server, FakeIdTokenProvider(token = null, refreshed = "new"))
        server.enqueue(problemResponse(401, "unauthorized"))

        val result = apiCall { api.create<MeApi>().getMe() }

        assertEquals(ApiResult.Failure(ApiFailure.Unauthorized(TEST_REQUEST_ID)), result)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(false), api.tokens.calls.toList())
    }

    @Test
    fun `a refresh that yields nothing or the same token gives up`() = runTest {
        for (refreshed in listOf(null, "old")) {
            val api = TestApi(server, FakeIdTokenProvider(token = "old", refreshed = refreshed))
            val before = server.requestCount
            server.enqueue(problemResponse(401, "unauthorized"))

            val result = apiCall { api.create<MeApi>().getMe() }

            assertEquals(ApiResult.Failure(ApiFailure.Unauthorized(TEST_REQUEST_ID)), result)
            assertEquals(1, server.requestCount - before)
            assertEquals(listOf(false, true), api.tokens.calls.toList())
        }
    }

    @Test
    fun `a token source that fails is a connection failure, not a crash`() = runTest {
        val failing = object : IdTokenProvider {
            override suspend fun idToken(forceRefresh: Boolean): String? = error("sign-in broke")
        }
        val config = TestApi(server).config
        val client = newApiHttpClient(config, failing, FIXED_CLOCK, RecordingSleeper())
        val api = newApiRetrofit(config) { client }.create(MeApi::class.java)

        val result = apiCall { api.getMe() }

        assertEquals(ApiResult.Failure(ApiFailure.NoConnection), result)
        assertEquals(0, server.requestCount)
    }
}
