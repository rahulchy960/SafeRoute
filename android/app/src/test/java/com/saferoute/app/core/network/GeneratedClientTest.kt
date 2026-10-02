// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network

import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.api.OperationalApi
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import com.saferoute.app.core.network.generated.model.Health
import com.saferoute.app.core.network.generated.model.Readiness
import com.saferoute.app.core.network.generated.model.ReadinessChecks
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The generated interfaces and models against the JSON the backend really sends: every
 * operation of contract 0.2.0, and the tolerance rules of ADR 0004.
 */
class GeneratedClientTest {

    private val server = MockWebServer()
    private lateinit var operational: OperationalApi
    private lateinit var me: MeApi

    @Before
    fun setUp() {
        server.start()
        val api = TestApi(server)
        operational = api.create()
        me = api.create()
    }

    @After
    fun tearDown() = server.close()

    private fun <T> ApiResult<T>.success(): ApiResult.Success<T> = this as ApiResult.Success<T>

    @Test
    fun `getHealth parses the liveness body`() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))

        val result = apiCall { operational.getHealth() }.success()

        assertEquals(Health(Health.Status.ok, "saferoute-api", "0.1.0", 42), result.value)
        assertEquals(200, result.status)
        assertEquals(TEST_REQUEST_ID, result.requestId)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/health", request.url.encodedPath)
    }

    @Test
    fun `getReadiness parses the readiness body`() = runTest {
        server.enqueue(jsonResponse(READINESS_JSON))

        val result = apiCall { operational.getReadiness() }.success()

        val expected = Readiness(Readiness.Status.ready, ReadinessChecks(ReadinessChecks.Database.ok))
        assertEquals(expected, result.value)
        assertEquals("/health/ready", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `getMe parses the account with typed id and timestamp`() = runTest {
        server.enqueue(jsonResponse(ME_JSON))

        val account = apiCall { me.getMe() }.success().value

        assertEquals(UUID.fromString("0f8c2a4e-6b1d-4c3a-9e7f-2d5b8a1c4e60"), account.id)
        assertEquals("+910000000001", account.phoneE164)
        assertEquals("Sample Name", account.displayName)
        assertEquals("en", account.locale)
        assertEquals("user", account.role)
        assertEquals(Instant.parse("2026-10-01T09:30:00Z"), account.createdAt.toInstant())
        assertEquals("/v1/me", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `nullable fields accept null`() = runTest {
        server.enqueue(
            jsonResponse(
                ME_JSON.replace("\"+910000000001\"", "null").replace("\"Sample Name\"", "null"),
            ),
        )

        val account = apiCall { me.getMe() }.success().value

        assertNull(account.phoneE164)
        assertNull(account.displayName)
    }

    @Test
    fun `bootstrapMe tells created from existing by the status`() = runTest {
        server.enqueue(jsonResponse(ME_JSON, code = 201))
        server.enqueue(jsonResponse(ME_JSON, code = 200))
        val body = BootstrapMeRequest(BootstrapMeRequest.Locale.bn, "Sample Name")

        val created = apiCall { me.bootstrapMe(body) }.success()
        val existing = apiCall { me.bootstrapMe(body) }.success()

        assertEquals(201, created.status)
        assertEquals(200, existing.status)
        assertEquals(created.value, existing.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/bootstrap", request.url.encodedPath)
        assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
        assertEquals("""{"locale":"bn","displayName":"Sample Name"}""", request.body?.utf8())
    }

    @Test
    fun `an empty bootstrap request is sent as an empty object`() = runTest {
        server.enqueue(jsonResponse(ME_JSON, code = 201))

        apiCall { me.bootstrapMe(BootstrapMeRequest()) }.success()

        assertEquals("{}", server.takeRequest().body?.utf8())
    }

    @Test
    fun `the generated null default for the body is refused before anything is sent`() = runTest {
        // Retrofit does not allow a null @Body (R0 finding): callers pass BootstrapMeRequest().
        val result = apiCall { me.bootstrapMe() }

        assertEquals(ApiResult.Failure(ApiFailure.Unexpected(status = null)), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unknown extra fields are ignored`() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON.replace("}", ""","region":"x","extra":{"a":[1]}}""")))
        server.enqueue(jsonResponse(ME_JSON.replace("}", ""","newField":true}""")))

        assertEquals("0.1.0", apiCall { operational.getHealth() }.success().value.version)
        assertEquals("en", apiCall { me.getMe() }.success().value.locale)
    }

    @Test
    fun `unknown values of open strings pass through`() = runTest {
        server.enqueue(
            jsonResponse(
                ME_JSON.replace("\"role\":\"user\"", "\"role\":\"superadmin\"")
                    .replace("\"locale\":\"en\"", "\"locale\":\"fr\""),
            ),
        )

        val account = apiCall { me.getMe() }.success().value

        assertEquals("superadmin", account.role)
        assertEquals("fr", account.locale)
    }

    @Test
    fun `an unknown value of a closed enum is a handled failure, not a crash`() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON.replace("\"ok\"", "\"degraded\"")))

        val result = apiCall { operational.getHealth() }

        assertEquals(ApiResult.Failure(ApiFailure.Unexpected(status = null)), result)
    }

    @Test
    fun `a success body that is not the contract's JSON is a handled failure`() = runTest {
        server.enqueue(jsonResponse("<html>ok</html>"))
        server.enqueue(jsonResponse("""{"status":"ok"}"""))

        assertEquals(ApiResult.Failure(ApiFailure.Unexpected(null)), apiCall { operational.getHealth() })
        assertEquals(ApiResult.Failure(ApiFailure.Unexpected(null)), apiCall { operational.getHealth() })
    }
}
