// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.errors

import com.saferoute.app.core.network.ME_JSON
import com.saferoute.app.core.network.TEST_REQUEST_ID
import com.saferoute.app.core.network.TestApi
import com.saferoute.app.core.network.di.newApiRetrofit
import com.saferoute.app.core.network.generated.api.MeApi
import com.saferoute.app.core.network.generated.api.OperationalApi
import com.saferoute.app.core.network.generated.model.BootstrapMeRequest
import com.saferoute.app.core.network.generated.model.Me
import com.saferoute.app.core.network.jsonResponse
import com.saferoute.app.core.network.problemJson
import com.saferoute.app.core.network.problemResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** From an HTTP error or an exception to an [ApiFailure], and what [apiCall] promises. */
class ApiErrorMappingTest {

    private val problemType = "application/problem+json"

    /** An error response as Retrofit hands it over, without a server. */
    private fun errorResponse(
        status: Int,
        body: String,
        contentType: String? = problemType,
        requestIdHeader: String? = "header-request-id",
    ): Response<Me> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.invalid/v1/me").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Error")
            .apply { requestIdHeader?.let { header("X-Request-Id", it) } }
            .build()
        return Response.error(body.toResponseBody(contentType?.toMediaType()), raw)
    }

    private suspend fun failureOf(response: Response<Me>): ApiFailure =
        (apiCall { response } as ApiResult.Failure).failure

    @Test
    fun `every known code maps to a Problem with the body's fields`() = runTest {
        val known = mapOf(
            ProblemCodes.VALIDATION_ERROR to 400,
            ProblemCodes.FORBIDDEN to 403,
            ProblemCodes.BOOTSTRAP_REQUIRED to 403,
            ProblemCodes.ACCOUNT_DELETED to 403,
            ProblemCodes.NOT_FOUND to 404,
            ProblemCodes.CONFLICT to 409,
            ProblemCodes.PHONE_ALREADY_REGISTERED to 409,
            ProblemCodes.GONE to 410,
            ProblemCodes.RATE_LIMITED to 429,
            ProblemCodes.INTERNAL_ERROR to 500,
            ProblemCodes.DB_UNAVAILABLE to 503,
            ProblemCodes.DB_NOT_CONFIGURED to 503,
            ProblemCodes.AUTH_UNAVAILABLE to 503,
            ProblemCodes.AUTH_NOT_CONFIGURED to 503,
            ProblemCodes.HTTP_ERROR to 405,
        )
        for ((code, status) in known) {
            val body = problemJson(status, code, title = "A title", detail = "A detail.")

            val failure = failureOf(errorResponse(status, body))

            val expected = ApiFailure.Problem(status, code, "A title", "A detail.", TEST_REQUEST_ID)
            assertEquals(expected, failure)
        }
    }

    @Test
    fun `an unknown code is kept as it is`() = runTest {
        val failure = failureOf(errorResponse(418, problemJson(418, "teapot_mode")))

        assertEquals("teapot_mode", (failure as ApiFailure.Problem).code)
    }

    @Test
    fun `validation errors carry the field errors`() = runTest {
        val errors = """[{"path":"body.displayName","code":"too_small"},{"path":"body.locale","code":"invalid_value"}]"""

        val failure = failureOf(errorResponse(400, problemJson(400, "validation_error", errors = errors)))

        val expected = listOf(
            FieldError("body.displayName", "too_small"),
            FieldError("body.locale", "invalid_value"),
        )
        assertEquals(expected, (failure as ApiFailure.Problem).fieldErrors)
    }

    @Test
    fun `plain application-json with a problem body is accepted`() = runTest {
        val response = errorResponse(404, problemJson(404, "not_found"), contentType = "application/json")

        assertEquals("not_found", (failureOf(response) as ApiFailure.Problem).code)
    }

    @Test
    fun `401 is Unauthorized whatever the body says`() = runTest {
        val failure = failureOf(errorResponse(401, problemJson(401, "unauthorized")))

        assertEquals(ApiFailure.Unauthorized("header-request-id"), failure)
    }

    @Test
    fun `bodies that are not a complete problem document become Unexpected`() = runTest {
        val notProblems = listOf(
            // required fields missing
            """{"code":"not_found","status":404}""" to problemType,
            // valid JSON, wrong shape
            """["not","an","object"]""" to problemType,
            // not JSON at all
            "upstream connect error" to problemType,
            // a gateway's HTML page
            "<html><body><h1>502 Bad Gateway</h1>secret-marker</body></html>" to "text/html",
            // no content type
            problemJson(502, "internal_error") to null,
            // empty
            "" to problemType,
        )
        for ((body, contentType) in notProblems) {
            val failure = failureOf(errorResponse(502, body, contentType))

            assertEquals(ApiFailure.Unexpected(502, "header-request-id"), failure)
            assertFalse(failure.toString().contains("secret-marker"))
        }
    }

    @Test
    fun `a huge body is not parsed`() = runTest {
        // A valid problem document, padded far beyond anything the backend sends.
        val padding = "x".repeat((MAX_PROBLEM_BYTES * 2).toInt())
        val body = problemJson(500, "internal_error", detail = padding)

        val failure = failureOf(errorResponse(500, body))

        assertEquals(ApiFailure.Unexpected(500, "header-request-id"), failure)
    }

    @Test
    fun `the request id falls back to the header when the body has none`() = runTest {
        val failure = failureOf(errorResponse(404, problemJson(404, "not_found", requestId = "")))

        assertEquals("header-request-id", (failure as ApiFailure.Problem).requestId)
    }

    @Test
    fun `retryable and final failures are told apart`() {
        fun problem(status: Int, code: String) = ApiFailure.Problem(status, code, "t", "d", null)

        assertTrue(isRetryable(ApiFailure.NoConnection))
        assertTrue(isRetryable(problem(503, ProblemCodes.DB_UNAVAILABLE)))
        assertTrue(isRetryable(problem(503, ProblemCodes.AUTH_UNAVAILABLE)))
        assertTrue(isRetryable(problem(503, "some_new_code")))
        assertTrue(isRetryable(ApiFailure.Unexpected(503)))
        assertFalse(isRetryable(problem(403, ProblemCodes.BOOTSTRAP_REQUIRED)))
        assertFalse(isRetryable(problem(400, ProblemCodes.VALIDATION_ERROR)))
        assertFalse(isRetryable(problem(500, ProblemCodes.INTERNAL_ERROR)))
        assertFalse(isRetryable(ApiFailure.Unauthorized()))
        assertFalse(isRetryable(ApiFailure.Unexpected(502)))

        assertTrue(problem(403, ProblemCodes.BOOTSTRAP_REQUIRED).isFinal403)
        assertTrue(problem(403, ProblemCodes.ACCOUNT_DELETED).isFinal403)
        assertTrue(problem(403, ProblemCodes.FORBIDDEN).isFinal403)
        assertTrue(problem(403, "some_new_code").isFinal403)
        assertFalse(problem(503, ProblemCodes.DB_UNAVAILABLE).isFinal403)
        assertFalse(ApiFailure.Unauthorized().isFinal403)
    }

    @Test
    fun `apiCall maps exceptions and lets cancellation through`() = runTest {
        assertEquals(
            ApiResult.Failure(ApiFailure.NoConnection),
            apiCall<Me> { throw IOException("unreachable") },
        )
        assertEquals(
            ApiResult.Failure(ApiFailure.Unexpected(null)),
            apiCall<Me> { throw IllegalStateException("bug") },
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { apiCall<Me> { throw CancellationException("screen closed") } }
        }
    }

    @Test
    fun `a success without a body is Unexpected`() = runTest {
        val result = apiCall { Response.success<Me>(204, null) }

        assertEquals(ApiResult.Failure(ApiFailure.Unexpected(204, null)), result)
    }

    // The same rules through the real client and a real (local) server.

    @Test
    fun `503 after the retries maps to a retryable Problem`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val api = TestApi(server)
            repeat(3) { server.enqueue(problemResponse(503, "db_unavailable")) }

            val result = apiCall { api.create<OperationalApi>().getReadiness() }

            val failure = (result as ApiResult.Failure).failure
            assertEquals("db_unavailable", (failure as ApiFailure.Problem).code)
            assertTrue(isRetryable(failure))
            assertEquals(3, server.requestCount)
            assertEquals(2, api.sleeper.sleeps.size)
        }
    }

    @Test
    fun `403 bootstrap_required is final and sent once`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val api = TestApi(server)
            server.enqueue(problemResponse(403, "bootstrap_required"))

            val failure = (apiCall { api.create<MeApi>().getMe() } as ApiResult.Failure).failure

            assertTrue(failure.isFinal403)
            assertFalse(isRetryable(failure))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a refused connection is NoConnection`() = runTest {
        val server = MockWebServer()
        server.start()
        val api = TestApi(server)
        server.close()

        val result = apiCall { api.create<MeApi>().bootstrapMe(BootstrapMeRequest()) }

        assertEquals(ApiResult.Failure(ApiFailure.NoConnection), result)
    }

    @Test
    fun `a timeout is NoConnection`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val base = TestApi(server)
            val impatient = base.client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
            val api = newApiRetrofit(base.config) { impatient }
                .create(MeApi::class.java)
            server.enqueue(
                jsonResponse(ME_JSON).newBuilder().headersDelay(5, TimeUnit.SECONDS).build(),
            )

            val result = apiCall { api.bootstrapMe(BootstrapMeRequest()) }

            assertEquals(ApiResult.Failure(ApiFailure.NoConnection), result)
        }
    }
}
