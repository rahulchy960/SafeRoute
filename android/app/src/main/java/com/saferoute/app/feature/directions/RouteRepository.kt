// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.di.DefaultDispatcher
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.network.errors.ApiFailure
import com.saferoute.app.core.network.errors.ApiResult
import com.saferoute.app.core.network.errors.ProblemCodes
import com.saferoute.app.core.network.errors.apiCall
import com.saferoute.app.core.network.generated.api.RoutingApi
import com.saferoute.app.core.network.generated.model.RoutePoint
import com.saferoute.app.core.network.generated.model.RouteRequest
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** How the person travels. Stored on the phone as [key]. */
enum class TravelMode(val key: String) {
    Walking("walking"),
    Driving("driving"),
    ;

    companion object {
        fun fromKey(key: String?): TravelMode = entries.firstOrNull { it.key == key } ?: Walking
    }
}

/** One way to get there. It says nothing about safety: a route is a line, a time and a length. */
data class RouteOption(
    val id: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<LatLng>,
) {
    // A route says where a person is and means to go: keep it out of logs.
    override fun toString(): String = "RouteOption(hidden)"
}

/** Why there are no routes to show. Each one has its own calm message on screen. */
sealed interface RouteError {
    /** A point lies outside the area routes exist for. A normal answer, not a failure. */
    data object OutsideCovered : RouteError

    /** Both points are fine, but no way connects them. */
    data object NoRoute : RouteError

    /** A point is not near a road or path. */
    data object NotRoutable : RouteError

    /** Too far apart for this way of travelling. */
    data object TooLong : RouteError

    data class RateLimited(val retryAfterSeconds: Int?) : RouteError

    /**
     * The routing service is waking up (it sleeps when nobody uses it) or briefly down: the
     * server answered 503 and said when to ask again.
     */
    data class Starting(val retryAfterSeconds: Int) : RouteError

    /** Routes cannot be computed right now, and the server did not say when. */
    data object Unavailable : RouteError

    /** The phone could not reach the server. */
    data object Offline : RouteError
}

sealed interface RouteOutcome {
    data class Found(val routes: List<RouteOption>, val attribution: String) : RouteOutcome {
        override fun toString(): String = "Found(hidden)"
    }

    data class Failed(val error: RouteError) : RouteOutcome
}

/**
 * Asks for routes. The app talks to the SafeRoute API only; the API asks its own routing
 * service (ADR 0020). Tests use `FakeRouteRepository`.
 */
interface RouteRepository {
    /** One request, no retry here: the caller decides whether and when to ask again. */
    suspend fun routes(origin: LatLng, destination: LatLng, mode: TravelMode): RouteOutcome
}

/** Six decimals are about 11 cm: finer than any phone knows where it is. */
private fun degrees(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)

class ApiRouteRepository @Inject constructor(
    private val api: RoutingApi,
    @DefaultDispatcher private val decodeDispatcher: CoroutineDispatcher,
) : RouteRepository {

    override suspend fun routes(origin: LatLng, destination: LatLng, mode: TravelMode): RouteOutcome {
        // A request BODY, never a URL: servers and platforms log URLs, and where a person is
        // and means to go must not end up there (ADR 0019).
        val request = RouteRequest(
            origin = RoutePoint(degrees(origin.latitude), degrees(origin.longitude)),
            destination = RoutePoint(degrees(destination.latitude), degrees(destination.longitude)),
            mode = if (mode == TravelMode.Driving) RouteRequest.Mode.driving else RouteRequest.Mode.walking,
        )
        return when (val result = apiCall { api.createRoutes(request) }) {
            is ApiResult.Failure -> RouteOutcome.Failed(result.failure.toRouteError())
            // Decoding a long line is real work: off the main thread, so the screen stays smooth.
            is ApiResult.Success -> withContext(decodeDispatcher) {
                val routes = result.value.routes.map { route ->
                    val points = decodePolyline6(route.geometry.value)?.takeIf { it.size >= 2 }
                        ?: return@withContext RouteOutcome.Failed(RouteError.Unavailable)
                    RouteOption(route.id, route.distanceMeters, route.durationSeconds, points)
                }
                if (routes.isEmpty()) {
                    RouteOutcome.Failed(RouteError.Unavailable)
                } else {
                    RouteOutcome.Found(routes, result.value.attribution)
                }
            }
        }
    }
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAVAILABLE = 503

/**
 * Decides on the problem `code`, as the contract asks. 401 and 403 are not handled here: they
 * fall into [RouteError.Unavailable], and the session (which checks the account on its own)
 * decides what the user sees next.
 */
internal fun ApiFailure.toRouteError(): RouteError = when (this) {
    ApiFailure.NoConnection -> RouteError.Offline
    is ApiFailure.Problem -> when {
        code == ProblemCodes.OUTSIDE_COVERED_AREA -> RouteError.OutsideCovered
        code == ProblemCodes.NO_ROUTE_FOUND -> RouteError.NoRoute
        code == ProblemCodes.LOCATION_NOT_ROUTABLE -> RouteError.NotRoutable
        code == ProblemCodes.ROUTE_TOO_LONG -> RouteError.TooLong
        status == HTTP_TOO_MANY_REQUESTS || code == ProblemCodes.RATE_LIMITED ->
            RouteError.RateLimited(retryAfterSeconds)
        status == HTTP_UNAVAILABLE ->
            retryAfterSeconds?.let(RouteError::Starting) ?: RouteError.Unavailable
        else -> RouteError.Unavailable
    }
    is ApiFailure.Unexpected -> when (status) {
        HTTP_TOO_MANY_REQUESTS -> RouteError.RateLimited(null)
        else -> RouteError.Unavailable
    }
    is ApiFailure.Unauthorized -> RouteError.Unavailable
}
