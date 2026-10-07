// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.network.errors

/**
 * Every way an API call can fail, in terms the rest of the app can act on. Screens and
 * ViewModels see this type and never an OkHttp or Retrofit class.
 *
 * A `sealed` type has a fixed list of subtypes, so a `when` over it must handle all of them.
 */
sealed interface ApiFailure {

    /**
     * The server answered with an error in the contract's format (`application/problem+json`,
     * ADR 0004). Decide on [code], never on [title] or [detail]. [code] is an open set: a newer
     * backend may send one that [ProblemCodes] does not list.
     */
    data class Problem(
        val status: Int,
        val code: String,
        val title: String,
        val detail: String,
        val requestId: String?,
        val fieldErrors: List<FieldError> = emptyList(),
        /** From the `Retry-After` header: whole seconds to wait, when the server says so. */
        val retryAfterSeconds: Int? = null,
    ) : ApiFailure

    /** 401 after the one token refresh and retry: the user has to sign in again. */
    data class Unauthorized(val requestId: String? = null) : ApiFailure

    /** The server could not be reached: no network, a timeout, a refused or broken connection. */
    data object NoConnection : ApiFailure

    /**
     * Anything else: an error without a readable problem body (a gateway's HTML page, say) or a
     * success whose body does not match the contract. [status] is null when there was none.
     * The body is never kept.
     */
    data class Unexpected(val status: Int?, val requestId: String? = null) : ApiFailure
}

/** One invalid input of a `validation_error`. Never contains the submitted value. */
data class FieldError(val path: String, val code: String)

/** The outcome of [apiCall]: a value, or the reason there is none. */
sealed interface ApiResult<out T> {

    /** [status] tells 200 from 201; [requestId] is the server's `X-Request-Id`. */
    data class Success<T>(val value: T, val status: Int, val requestId: String?) : ApiResult<T>

    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

/** The error codes the backend defines today (contract 0.4.0). Unknown codes must be tolerated. */
object ProblemCodes {
    const val VALIDATION_ERROR = "validation_error"
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val BOOTSTRAP_REQUIRED = "bootstrap_required"
    const val ACCOUNT_DELETED = "account_deleted"
    const val CONSENT_REQUIRED = "consent_required"
    const val ADULT_REQUIRED = "adult_required"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val PHONE_ALREADY_REGISTERED = "phone_already_registered"
    const val ACCOUNT_DELETION_REQUIRED = "account_deletion_required"
    const val GONE = "gone"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL_ERROR = "internal_error"
    const val DB_UNAVAILABLE = "db_unavailable"
    const val DB_NOT_CONFIGURED = "db_not_configured"
    const val AUTH_UNAVAILABLE = "auth_unavailable"
    const val AUTH_NOT_CONFIGURED = "auth_not_configured"
    const val SEARCH_UNAVAILABLE = "search_unavailable"
    const val SEARCH_NOT_CONFIGURED = "search_not_configured"
    const val HTTP_ERROR = "http_error"
}

private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNAVAILABLE = 503

/**
 * True for every 403, whatever its code (`forbidden`, `bootstrap_required`, `account_deleted`
 * or one this version does not know): sending the same request again cannot succeed (ADR 0006).
 */
val ApiFailure.isFinal403: Boolean
    get() = this is ApiFailure.Problem && status == HTTP_FORBIDDEN

/**
 * True when trying again later may work: no connection, any 503, or the codes that mean a
 * dependency is down. Whether to actually retry is the caller's decision; a write must only be
 * repeated with an `Idempotency-Key`.
 */
fun isRetryable(failure: ApiFailure): Boolean = when (failure) {
    ApiFailure.NoConnection -> true
    is ApiFailure.Problem ->
        failure.status == HTTP_UNAVAILABLE ||
            failure.code == ProblemCodes.AUTH_UNAVAILABLE ||
            failure.code == ProblemCodes.DB_UNAVAILABLE
    is ApiFailure.Unexpected -> failure.status == HTTP_UNAVAILABLE
    is ApiFailure.Unauthorized -> false
}
