// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One route as the map needs it: a name to tell it apart, and its line. */
data class RouteLine(val id: String, val points: List<LatLng>) {
    override fun toString(): String = "RouteLine(hidden)"
}

/**
 * The routes to draw, which of them is chosen, and where they start.
 *
 * @property fitToken changes whenever the map should show the whole selected route again (new
 * routes arrived). Choosing another alternative does not change it: the map stays where the
 * user put it.
 */
data class ShownRoutes(
    val lines: List<RouteLine>,
    val selectedId: String,
    val start: LatLng,
    val fitToken: Int,
) {
    override fun toString(): String = "ShownRoutes(hidden)"
}

/**
 * A route that is being followed, as the map needs it: what lies behind the person, and where
 * the camera belongs.
 *
 * @property bearing the direction of travel, degrees clockwise from north; 0 (north up) while
 * the person stands still.
 */
data class FollowView(val travelled: List<LatLng>, val position: LatLng, val bearing: Double) {
    override fun toString(): String = "FollowView(hidden)"
}

/**
 * What the directions feature asks the map screen to draw. Directions and Home have separate
 * ViewModels; this small object connects them without either knowing the other, the same way
 * [MapSelection] connects search and Home.
 *
 * Routes say where a person is and means to go. They live here in memory only, for as long as
 * the activity does: never saved, never logged, never sent anywhere.
 */
@ActivityRetainedScoped
class RouteDisplay @Inject constructor() {

    private val _routes = MutableStateFlow<ShownRoutes?>(null)

    /** The routes on the map, or null when there are none. */
    val routes: StateFlow<ShownRoutes?> = _routes.asStateFlow()

    private val _following = MutableStateFlow<FollowView?>(null)

    /** Set while the user follows the selected route (ADR 0022), null otherwise. */
    val following: StateFlow<FollowView?> = _following.asStateFlow()

    private var fits = 0

    fun follow(view: FollowView?) {
        _following.value = view
    }

    /** New routes: draw them and show the selected one whole. */
    fun show(lines: List<RouteLine>, selectedId: String, start: LatLng) {
        _routes.value = ShownRoutes(lines, selectedId, start, fitToken = ++fits)
    }

    /** Show the selected route whole again, as when it arrived ("Preview"). */
    fun refit() {
        val current = _routes.value ?: return
        _routes.value = current.copy(fitToken = ++fits)
    }

    /** Another of the routes already shown was chosen. */
    fun select(routeId: String) {
        val current = _routes.value ?: return
        if (current.lines.any { it.id == routeId }) _routes.value = current.copy(selectedId = routeId)
    }

    fun clear() {
        _following.value = null
        _routes.value = null
    }
}

private const val ROUTE_OVERLAY_PREFIX = "route-"

/** The route a route overlay's id stands for, or null for any other overlay. */
fun routeIdOf(overlayId: String): String? =
    overlayId.takeIf { it.startsWith(ROUTE_OVERLAY_PREFIX) }?.removePrefix(ROUTE_OVERLAY_PREFIX)

/** The box around a line, or null for an empty one. */
fun boundsOf(points: List<LatLng>): LatLngBounds? {
    if (points.isEmpty()) return null
    return LatLngBounds(
        southWest = LatLng(points.minOf { it.latitude }, points.minOf { it.longitude }),
        northEast = LatLng(points.maxOf { it.latitude }, points.maxOf { it.longitude }),
    )
}

/**
 * Overlay descriptions for [shown]: the alternatives first, the selected route on top of them,
 * then a ring where the route starts. The destination pin is the chosen place's own marker.
 *
 * While a route is followed ([following]) only that route is drawn, with the part already
 * travelled muted on top of it.
 */
fun routeOverlays(shown: ShownRoutes, following: FollowView? = null): List<MapOverlay> {
    val (selected, others) = shown.lines.partition { it.id == shown.selectedId }
    if (following != null) {
        val behind = following.travelled.takeIf { it.size >= 2 }
            ?.let { MapOverlay.Route("route-travelled", it, selected = false, travelled = true) }
        return selected.map { MapOverlay.Route("route-${it.id}", it.points, selected = true) } + listOfNotNull(behind)
    }
    return others.map { MapOverlay.Route("route-${it.id}", it.points, selected = false) } +
        selected.map { MapOverlay.Route("route-${it.id}", it.points, selected = true) } +
        MapOverlay.Marker(id = "route-start", position = shown.start, style = MarkerStyle.RouteStart)
}
