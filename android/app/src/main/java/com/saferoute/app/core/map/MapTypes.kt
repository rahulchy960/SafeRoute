// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import kotlinx.coroutines.flow.StateFlow

/*
 * The map vocabulary the rest of the app uses. Plain Kotlin: nothing here comes from the map
 * library, so screens, ViewModels and tests never depend on it (ADR 0015, "Switching the map
 * provider").
 */

/**
 * A point on the earth in degrees (WGS 84). Latitude first, as people say it; the backend and
 * GeoJSON use longitude first (`backend/src/db/README.md`), so convert at that boundary.
 */
data class LatLng(val latitude: Double, val longitude: Double) {
    // A position can be where a person is: the generated toString() would print it into logs.
    override fun toString(): String = "LatLng(hidden)"
}

/**
 * Where the map is looking.
 *
 * @property zoom 0 shows the whole world; about 12 a city; about 16 a few streets.
 * @property bearing degrees clockwise from north that the top of the screen points at; 0 = north up.
 */
data class CameraState(val target: LatLng, val zoom: Double, val bearing: Double = 0.0) {
    override fun toString(): String = "CameraState(hidden)"
}

/**
 * Parts of the map view, in pixels, that are covered by other things (search pill, bottom
 * sheet). The map keeps its focus point and its own controls inside what is left.
 */
data class MapPadding(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Which look the map uses. It follows the app theme, not the map provider's defaults. */
enum class MapStyleVariant { Light, Dark }

/** What the map area is doing. The screen draws a calm message for every state but [Ready]. */
sealed interface MapLoadState {
    /** The style is being fetched. */
    data object Loading : MapLoadState

    /** The map is drawn and usable. Also the state while it works offline from its cache. */
    data object Ready : MapLoadState

    /** Loading failed for a reason that trying again may fix. */
    data object Error : MapLoadState

    /** There is no connection and nothing (or not enough) in the cache to draw. */
    data object Offline : MapLoadState

    /** This build has no map key (`saferoute.mapTilerKey`). */
    data object NotConfigured : MapLoadState

    /** The provider refused the key or the quota is used up. Shown as "temporarily unavailable". */
    data object RateLimited : MapLoadState
}

/**
 * Things drawn on top of the map, described as data. A screen says *what* to show; the map
 * component decides how to draw it with the library in use. Part b (P010b) draws the first
 * ones; routes and areas follow in later prompts.
 */
sealed interface MapOverlay {
    /** Stable name, so that a redraw updates the same shape instead of adding another. */
    val id: String

    data class Marker(
        override val id: String,
        val position: LatLng,
        val headingDegrees: Double? = null,
        val style: MarkerStyle = MarkerStyle.Default,
    ) : MapOverlay

    data class AccuracyCircle(
        override val id: String,
        val center: LatLng,
        val radiusMeters: Double,
        val style: MarkerStyle = MarkerStyle.Default,
    ) : MapOverlay

    data class Polyline(override val id: String, val points: List<LatLng>) : MapOverlay

    data class Polygons(override val id: String, val rings: List<List<LatLng>>) : MapOverlay
}

/** How a marker or its circle looks. Colours come from the design system, never from here. */
enum class MarkerStyle { Default, Approximate, Stale }

/**
 * What a screen can ask of the map and read from it. The only door between the app and the map
 * library: swapping the library means writing a new implementation of this and of [MapEngine].
 */
interface MapController {
    val loadState: StateFlow<MapLoadState>

    /** Where the map was looking when it last came to rest. */
    val camera: StateFlow<CameraState>

    fun setPadding(padding: MapPadding)

    fun setStyleVariant(variant: MapStyleVariant)

    /** Tries to load the map again after [MapLoadState.Error], Offline or RateLimited. */
    fun retry()

    fun moveCamera(camera: CameraState, animate: Boolean = true)

    fun setOverlays(overlays: List<MapOverlay>)
}
