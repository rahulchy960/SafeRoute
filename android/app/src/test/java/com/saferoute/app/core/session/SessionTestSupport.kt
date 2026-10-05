// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.session

import com.saferoute.app.core.network.TEST_REQUEST_ID
import com.saferoute.app.core.network.interceptor.REQUEST_ID_HEADER
import com.saferoute.app.core.network.jsonResponse
import com.saferoute.app.core.network.problemResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/** [SessionStore] that lives in memory, for tests that are not about the file. */
class InMemorySessionStore(initial: SessionFlags = SessionFlags()) : SessionStore {
    private val state = MutableStateFlow(initial)

    /** How many times something was written; the under-18 test checks it is exactly once. */
    var writes = 0
        private set

    val current: SessionFlags get() = state.value

    override val flags: Flow<SessionFlags> = state

    override suspend fun update(transform: (SessionFlags) -> SessionFlags) {
        writes++
        state.value = transform(state.value)
    }

    override suspend fun clear() {
        writes++
        state.value = SessionFlags()
    }
}

/** Flags of a user who passed the age gate and accepted the current notice in English. */
val ONBOARDED = SessionFlags(
    welcomeSeen = true,
    ageConfirmed = true,
    acceptedNoticeVersion = NOTICE_VERSION,
    acceptedNoticeLocale = LOCALE_ENGLISH,
)

fun consentsJson(vararg items: String): String = """{"items":[${items.joinToString(",")}]}"""

fun consentJson(
    purpose: String = PURPOSE_ACCOUNT_CORE,
    status: String = "granted",
    noticeVersion: String = NOTICE_VERSION,
): String =
    """{"purpose":"$purpose","status":"$status","noticeVersion":"$noticeVersion",""" +
        """"decidedAt":"2026-10-06T09:30:00.000Z"}"""

data class Recorded(val route: String, val body: String, val authorization: String?)

/**
 * The fake SafeRoute API: one answer per "METHOD path". A route answers with its responses in
 * order and repeats the last one. A route nobody defined answers 404, so an unexpected call
 * shows up in the test.
 */
class FakeApi : Dispatcher() {
    private val routes = ConcurrentHashMap<String, List<MockResponse>>()
    private val served = ConcurrentHashMap<String, Int>()
    val requests = CopyOnWriteArrayList<Recorded>()

    fun on(route: String, vararg responses: MockResponse) = apply {
        routes[route] = responses.toList()
        served[route] = 0
    }

    fun routesCalled(): List<String> = requests.map { it.route }

    fun bodyOf(route: String): String = requests.last { it.route == route }.body

    override fun dispatch(request: RecordedRequest): MockResponse {
        val route = "${request.method} ${request.url.encodedPath}"
        requests += Recorded(route, request.body?.utf8().orEmpty(), request.headers["Authorization"])
        val responses = routes[route] ?: return problemResponse(404, "not_found")
        val index = served.merge(route, 1, Int::plus)!! - 1
        return responses[index.coerceAtMost(responses.lastIndex)]
    }
}

const val GET_ME = "GET /v1/me"
const val GET_CONSENTS = "GET /v1/me/consents"
const val PUT_ACCOUNT_CORE = "PUT /v1/me/consents/account_core"
const val POST_BOOTSTRAP = "POST /v1/me/bootstrap"

/** A 401 as the backend sends it. */
fun unauthorizedResponse(): MockResponse = MockResponse.Builder()
    .code(401)
    .addHeader("Content-Type", "application/problem+json")
    .addHeader("WWW-Authenticate", "Bearer")
    .addHeader(REQUEST_ID_HEADER, TEST_REQUEST_ID)
    .body("""{"type":"about:blank","title":"Unauthorized","status":401,"detail":"x","code":"unauthorized","requestId":"$TEST_REQUEST_ID"}""")
    .build()

/** An error page from something in front of the API: not a problem document. */
fun htmlResponse(status: Int): MockResponse = MockResponse.Builder()
    .code(status)
    .addHeader("Content-Type", "text/html")
    .body("<html><body>Bad gateway</body></html>")
    .build()

fun ok(json: String, code: Int = 200): MockResponse = jsonResponse(json, code)
