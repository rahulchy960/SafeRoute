// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Turns overlay descriptions into GeoJSON, the text format map libraries draw from. Plain
 * Kotlin on purpose: what is drawn (shape, size, colour, which style) is decided and tested
 * here; the map library only paints the result.
 */

/** Colours for overlays as `#RRGGBB`, taken from the design system by the map component. */
data class OverlayPalette(
    /** The current-location dot and its circle. Never the SOS red. */
    val location: String,
    /** An old position: grey. */
    val stale: String,
    /** The ring around the dot, so that it stands out on any map. */
    val halo: String,
    val line: String,
)

internal object OverlayKind {
    const val FILL = "fill"
    const val LINE = "line"
    const val DOT = "dot"
    const val HEADING = "heading"
}

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val CIRCLE_STEPS = 48

/**
 * The outline of a circle on the ground as a closed ring of points. A map has no "circle of 30
 * metres" shape, so the accuracy circle is a polygon with enough corners to look round. Its
 * size is in metres, so it grows and shrinks with the zoom like everything else on the map.
 */
internal fun circleRing(center: LatLng, radiusMeters: Double, steps: Int = CIRCLE_STEPS): List<LatLng> {
    val angular = radiusMeters / EARTH_RADIUS_METERS
    val lat1 = center.latitude.toRadians()
    val lng1 = center.longitude.toRadians()
    val ring = (0 until steps).map { step ->
        val bearing = 2 * PI * step / steps
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lng2 = lng1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        LatLng(lat2.toDegrees(), lng2.toDegrees())
    }
    // GeoJSON wants the first point repeated at the end.
    return ring + ring.first()
}

/** A GeoJSON FeatureCollection for [overlays]. Coordinates are longitude first, as GeoJSON is. */
internal fun overlaysToGeoJson(overlays: List<MapOverlay>, palette: OverlayPalette): String {
    val features = overlays.flatMap { overlay ->
        when (overlay) {
            is MapOverlay.AccuracyCircle -> listOf(
                feature(
                    geometry = polygon(circleRing(overlay.center, overlay.radiusMeters)),
                    kind = OverlayKind.FILL,
                    color = overlay.style.color(palette),
                    opacity = 0.15,
                ),
            )
            is MapOverlay.Marker -> listOfNotNull(
                feature(
                    geometry = point(overlay.position),
                    kind = OverlayKind.DOT,
                    color = overlay.style.color(palette),
                    // Approximate: a ring, not a dot. Shape, not only colour, says "roughly here".
                    opacity = if (overlay.style == MarkerStyle.Approximate) 0.0 else 1.0,
                    stroke = if (overlay.style == MarkerStyle.Approximate) palette.location else palette.halo,
                ),
                overlay.headingDegrees?.takeIf { overlay.style == MarkerStyle.Default }?.let {
                    feature(
                        geometry = point(overlay.position),
                        kind = OverlayKind.HEADING,
                        color = palette.location,
                        heading = it,
                    )
                },
            )
            is MapOverlay.Polyline -> listOf(
                feature(
                    geometry = """{"type":"LineString","coordinates":${coordinates(overlay.points)}}""",
                    kind = OverlayKind.LINE,
                    color = palette.line,
                ),
            )
            is MapOverlay.Polygons -> overlay.rings.map { ring ->
                feature(
                    geometry = polygon(ring),
                    kind = OverlayKind.FILL,
                    color = palette.line,
                    opacity = 0.2,
                )
            }
        }
    }
    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

private fun MarkerStyle.color(palette: OverlayPalette): String =
    if (this == MarkerStyle.Stale) palette.stale else palette.location

private fun feature(
    geometry: String,
    kind: String,
    color: String,
    opacity: Double = 1.0,
    stroke: String = color,
    heading: Double? = null,
): String {
    val headingProperty = heading?.let { ""","heading":${number(it)}""" }.orEmpty()
    val properties =
        """{"kind":"$kind","color":"$color","opacity":${number(opacity)},"stroke":"$stroke"$headingProperty}"""
    return """{"type":"Feature","geometry":$geometry,"properties":$properties}"""
}

private fun point(position: LatLng): String =
    """{"type":"Point","coordinates":${coordinate(position)}}"""

private fun polygon(ring: List<LatLng>): String =
    """{"type":"Polygon","coordinates":[${coordinates(ring)}]}"""

private fun coordinates(points: List<LatLng>): String =
    points.joinToString(",", prefix = "[", postfix = "]", transform = ::coordinate)

private fun coordinate(position: LatLng): String =
    "[${number(position.longitude)},${number(position.latitude)}]"

// Locale.ROOT: a decimal comma (as in many languages) would break the JSON.
private fun number(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

private fun Double.toRadians(): Double = this * PI / 180

private fun Double.toDegrees(): Double = this * 180 / PI
