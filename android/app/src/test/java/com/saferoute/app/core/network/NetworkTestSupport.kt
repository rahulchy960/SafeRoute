// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.auth.IdTokenProvider
import com.saferoute.app.core.network.di.newApiHttpClient
import com.saferoute.app.core.network.di.newApiRetrofit
import com.saferoute.app.core.network.interceptor.NetworkLog
import com.saferoute.app.core.network.interceptor.REQUEST_ID_HEADER
import com.saferoute.app.core.network.retry.Sleeper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/*
 * Shared pieces for the network tests. MockWebServer is a real HTTP server on this machine
 * that answers with whatever a test queued, so the real client, interceptors and JSON decoding
 * run exactly as in the app; only the server is fake. No phone or emulator is involved.
 *
 * The JSON bodies below are the shapes the backend produces (backend/src/routes/health.ts,
 * ready.ts, modules/users/service.ts, lib/problem.ts) with the contract's example values.
 */

const val TEST_VERSION = "9.9-test"
const val TEST_REQUEST_ID = "req-test-abcdef"

const val HEALTH_JSON =
    """{"status":"ok","service":"saferoute-api","version":"0.1.0","uptimeSeconds":42}"""
const val READINESS_JSON = """{"status":"ready","checks":{"database":"ok"}}"""
const val ME_JSON =
    """{"id":"0f8c2a4e-6b1d-4c3a-9e7f-2d5b8a1c4e60","phoneE164":"+910000000001",""" +
        """"displayName":"Sample Name","locale":"en","role":"user",""" +
        """"createdAt":"2026-10-01T09:30:00.000Z"}"""

val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC)

/** A problem body as `problemResponse` in the backend writes it. */
fun problemJson(
    status: Int,
    code: String,
    title: String = "Error",
    detail: String = "Something generic.",
    requestId: String = TEST_REQUEST_ID,
    errors: String? = null,
): String =
    """{"type":"about:blank","title":"$title","status":$status,"detail":"$detail",""" +
        """"code":"$code","requestId":"$requestId"${errors?.let { ""","errors":$it""" } ?: ""}}"""

fun jsonResponse(body: String, code: Int = 200): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .addHeader(REQUEST_ID_HEADER, TEST_REQUEST_ID)
    .body(body)
    .build()

fun problemResponse(status: Int, code: String): MockResponse = MockResponse.Builder()
    .code(status)
    .addHeader("Content-Type", "application/problem+json")
    .addHeader(REQUEST_ID_HEADER, TEST_REQUEST_ID)
    .body(problemJson(status, code))
    .build()

/** Hands out [token] normally and [refreshed] when asked to refresh; remembers every call. */
class FakeIdTokenProvider(
    var token: String? = null,
    var refreshed: String? = null,
) : IdTokenProvider {
    /** One entry per call: the value of `forceRefresh`. */
    val calls = CopyOnWriteArrayList<Boolean>()

    override suspend fun idToken(forceRefresh: Boolean): String? {
        calls += forceRefresh
        return if (forceRefresh) refreshed else token
    }
}

/** Records the waits instead of waiting, so retry tests take no real time. */
class RecordingSleeper : Sleeper {
    val sleeps = CopyOnWriteArrayList<Long>()

    override fun sleep(millis: Long) {
        sleeps += millis
    }
}

class RecordingLog : NetworkLog {
    val lines = CopyOnWriteArrayList<String>()

    override fun log(line: String) {
        lines += line
    }
}

/** The app's real client and Retrofit, pointed at a [MockWebServer]. */
class TestApi(
    server: MockWebServer,
    val tokens: FakeIdTokenProvider = FakeIdTokenProvider(),
    val sleeper: RecordingSleeper = RecordingSleeper(),
    val log: RecordingLog = RecordingLog(),
    debugLogging: Boolean = false,
) {
    val config = ApiConfig(
        baseUrl = server.url("/"),
        isConfigured = true,
        versionName = TEST_VERSION,
        debugLogging = debugLogging,
    )
    val client: OkHttpClient = newApiHttpClient(config, tokens, FIXED_CLOCK, sleeper, log)
    val retrofit: Retrofit = newApiRetrofit(config) { client }

    inline fun <reified T> create(): T = retrofit.create(T::class.java)
}
