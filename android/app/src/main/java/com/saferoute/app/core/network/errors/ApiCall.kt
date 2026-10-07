// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.errors

import com.saferoute.app.core.network.generated.model.ProblemDetails
import com.saferoute.app.core.network.interceptor.REQUEST_ID_HEADER
import com.saferoute.app.core.network.networkJson
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response

private const val HTTP_UNAUTHORIZED = 401
private const val RETRY_AFTER_HEADER = "Retry-After"
private const val MAX_RETRY_AFTER_SECONDS = 86_400

/** A problem body is a few hundred bytes. Anything far bigger is not one and is not parsed. */
internal const val MAX_PROBLEM_BYTES = 64L * 1024

/**
 * Runs one call of a generated API interface and turns whatever happens into an [ApiResult]:
 *
 * ```
 * val result = apiCall { operationalApi.getHealth() }
 * ```
 *
 * It never throws, with one exception: [CancellationException]. That is how coroutines stop
 * work nobody waits for any more (the screen was closed), and swallowing it would break that.
 */
suspend fun <T : Any> apiCall(block: suspend () -> Response<T>): ApiResult<T> =
    try {
        val response = block()
        val body = response.body()
        when {
            !response.isSuccessful -> ApiResult.Failure(response.toApiFailure())
            body == null -> ApiResult.Failure(ApiFailure.Unexpected(response.code(), response.requestId()))
            else -> ApiResult.Success(body, response.code(), response.requestId())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Failure(ApiFailure.NoConnection)
    } catch (e: Exception) {
        // Includes a success body that does not match the contract (SerializationException).
        ApiResult.Failure(ApiFailure.Unexpected(status = null))
    }

private fun Response<*>.requestId(): String? = headers()[REQUEST_ID_HEADER]

/**
 * Maps an error response (4xx/5xx). The body is decoded with the generated [ProblemDetails]; if
 * it is missing, too big, not JSON or not a complete problem document, the result is
 * [ApiFailure.Unexpected] with the status only. The body's text is never kept or logged.
 */
internal fun Response<*>.toApiFailure(): ApiFailure {
    val status = code()
    val headerRequestId = requestId()
    if (status == HTTP_UNAUTHORIZED) return ApiFailure.Unauthorized(headerRequestId)

    val problem = readProblem() ?: return ApiFailure.Unexpected(status, headerRequestId)
    return ApiFailure.Problem(
        status = status,
        code = problem.code,
        title = problem.title,
        detail = problem.detail,
        requestId = problem.requestId.ifBlank { headerRequestId },
        fieldErrors = problem.errors.orEmpty().map { FieldError(it.path, it.code) },
        // Only the "seconds" form, and only a sensible value; anything else counts as absent.
        retryAfterSeconds = headers()[RETRY_AFTER_HEADER]?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS },
    )
}

private fun Response<*>.readProblem(): ProblemDetails? {
    val body = errorBody() ?: return null
    // `application/problem+json` (the contract) or plain `application/json`; never HTML or text.
    if (body.contentType()?.subtype?.endsWith("json") != true) return null
    return try {
        val source = body.source()
        // request() buffers up to that many bytes; a longer body is left alone.
        source.request(MAX_PROBLEM_BYTES + 1)
        if (source.buffer.size > MAX_PROBLEM_BYTES) return null
        networkJson.decodeFromString(ProblemDetails.serializer(), source.buffer.readUtf8())
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
