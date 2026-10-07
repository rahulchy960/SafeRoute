// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.search

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.ProblemCodes
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.generated.api.SearchApi
import com.saferoute.app.core.network.generated.model.SearchRequest
import com.saferoute.app.core.session.LOCALE_BENGALI
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/** One search result, in the app's own terms. */
data class FoundPlace(
    val id: String,
    val name: String,
    val label: String,
    val position: LatLng,
    /** What kind of place the provider says it is. An open set: unknown values are normal. */
    val kind: String,
) {
    // A result says where the user may be going: keep it out of logs.
    override fun toString(): String = "FoundPlace(hidden)"
}

/** Why a search has no results to show. */
sealed interface SearchError {
    /** The server could not be reached. */
    data object NoConnection : SearchError

    /** Too many searches. [retryAfterSeconds] is how long the server asks to wait, if it said. */
    data class RateLimited(val retryAfterSeconds: Int?) : SearchError

    /** Search is down on the server side. Not the user's fault, and not a sign-in problem. */
    data object Unavailable : SearchError

    /** Anything else. */
    data object Unexpected : SearchError
}

sealed interface SearchOutcome {
    data class Found(val places: List<FoundPlace>, val attribution: String?) : SearchOutcome {
        override fun toString(): String = "Found(hidden)"
    }

    data class Failed(val error: SearchError) : SearchOutcome
}

/**
 * Looks places up. The app talks to the SafeRoute API only; the API forwards the search to a
 * geocoding provider (ADR 0018). Tests use `FakeSearchRepository`.
 */
interface SearchRepository {
    /**
     * @param query what the user typed, trimmed.
     * @param near the area to prefer: the centre of the map, or null for the server's default.
     * @param language `en` or `bn`.
     */
    suspend fun search(query: String, near: LatLng?, language: String): SearchOutcome
}

/** How many results the app asks for. */
internal const val SEARCH_RESULT_LIMIT = 6

/**
 * Rounds a coordinate to two decimals, about 1 km. The server does the same; doing it here too
 * means a finer position never leaves the phone for a search.
 */
internal fun coarse(degrees: Double): BigDecimal = BigDecimal.valueOf(degrees).setScale(2, RoundingMode.HALF_UP)

class ApiSearchRepository @Inject constructor(private val api: SearchApi) : SearchRepository {

    override suspend fun search(query: String, near: LatLng?, language: String): SearchOutcome {
        // A request BODY, never a URL: servers and platforms log URLs, and what a person
        // searches for must not end up there (ADR 0019).
        val request = SearchRequest(
            q = query,
            nearLatitude = near?.let { coarse(it.latitude) },
            nearLongitude = near?.let { coarse(it.longitude) },
            language = if (language == LOCALE_BENGALI) SearchRequest.Language.bn else SearchRequest.Language.en,
            limit = SEARCH_RESULT_LIMIT,
        )
        val result = apiCall { api.searchPlaces(request) }
        return when (result) {
            is ApiResult.Success -> SearchOutcome.Found(
                places = result.value.results.map {
                    FoundPlace(
                        id = it.id,
                        name = it.name,
                        label = it.label,
                        position = LatLng(it.latitude.toDouble(), it.longitude.toDouble()),
                        kind = it.kind,
                    )
                },
                attribution = result.value.attribution?.takeIf(String::isNotBlank),
            )
            is ApiResult.Failure -> SearchOutcome.Failed(result.failure.toSearchError())
        }
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAVAILABLE = 503

/**
 * 401 and 403 are not handled here: they fall into [SearchError.Unexpected], and the session
 * (which checks the account on its own) decides what the user sees next.
 */
internal fun ApiFailure.toSearchError(): SearchError = when (this) {
    ApiFailure.NoConnection -> SearchError.NoConnection
    is ApiFailure.Problem -> when {
        status == HTTP_TOO_MANY_REQUESTS || code == ProblemCodes.RATE_LIMITED ->
            SearchError.RateLimited(retryAfterSeconds)
        status == HTTP_UNAVAILABLE -> SearchError.Unavailable
        else -> SearchError.Unexpected
    }
    is ApiFailure.Unexpected -> when (status) {
        HTTP_TOO_MANY_REQUESTS -> SearchError.RateLimited(null)
        HTTP_UNAVAILABLE -> SearchError.Unavailable
        else -> SearchError.Unexpected
    }
    is ApiFailure.Unauthorized -> SearchError.Unexpected
}
