// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.location.LocationFix
import com.saferoute.app.core.map.LatLng
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Where a person is along a route they follow (ADR 0022). Plain Kotlin and plain arithmetic: no
 * Android, no clock of its own, no network. Everything here lives in memory for as long as the
 * route is followed, and nothing prints a position.
 */

private const val METERS_PER_DEGREE = 111_320.0

/**
 * Metres between two points that are close together ("equirectangular": the earth treated as
 * flat around them). Off by far less than a GPS fix over the distances used here.
 */
internal fun metersBetween(a: LatLng, b: LatLng): Double {
    val east = (b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)) * METERS_PER_DEGREE
    val north = (b.latitude - a.latitude) * METERS_PER_DEGREE
    return hypot(east, north)
}

/** The nearest point of a stretch of the line to a position. */
internal class LinePosition(val segment: Int, val alongMeters: Double, val offMeters: Double, val point: LatLng)

/** A route's line with its length measured once. */
internal class RouteGeometry(val points: List<LatLng>) {

    /** `along[i]` is the length of the line from its start to point `i`. */
    private val along = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) along[i] = along[i - 1] + metersBetween(points[i - 1], points[i])
    }

    val lengthMeters: Double get() = along.lastOrNull() ?: 0.0

    /**
     * The nearest point to [position] on the part of the line between [fromMeters] and
     * [toMeters]. Where the line passes the position more than once (a loop, a road walked out
     * and back), the first pass that is about as near as the nearest one wins: progress moves on
     * step by step and never jumps ahead to the later pass.
     */
    fun locate(position: LatLng, fromMeters: Double, toMeters: Double): LinePosition? {
        var nearest = Double.MAX_VALUE
        val candidates = ArrayList<LinePosition>()
        for (i in 0 until points.size - 1) {
            if (along[i + 1] < fromMeters) continue
            if (along[i] > toMeters) break
            val candidate = project(position, i, fromMeters, toMeters)
            candidates += candidate
            nearest = minOf(nearest, candidate.offMeters)
        }
        return candidates.firstOrNull { it.offMeters <= nearest + SAME_PASS_SLACK_METERS }
    }

    /** The line from its start to [meters] along it, for drawing the part already travelled. */
    fun lineUpTo(segment: Int, meters: Double): List<LatLng> {
        if (points.size < 2) return emptyList()
        val length = along[segment + 1] - along[segment]
        val t = if (length == 0.0) 0.0 else ((meters - along[segment]) / length).coerceIn(0.0, 1.0)
        return points.subList(0, segment + 1) + between(points[segment], points[segment + 1], t)
    }

    /** The nearest point of segment [i] that lies between [fromMeters] and [toMeters] along the line. */
    private fun project(position: LatLng, i: Int, fromMeters: Double, toMeters: Double): LinePosition {
        val a = points[i]
        val b = points[i + 1]
        // Flat metres around the position: x east, y north.
        val scale = cos(Math.toRadians(position.latitude)) * METERS_PER_DEGREE
        val ax = (a.longitude - position.longitude) * scale
        val ay = (a.latitude - position.latitude) * METERS_PER_DEGREE
        val dx = (b.longitude - a.longitude) * scale
        val dy = (b.latitude - a.latitude) * METERS_PER_DEGREE
        val squared = dx * dx + dy * dy
        val length = along[i + 1] - along[i]
        val t = if (squared == 0.0 || length == 0.0) {
            0.0
        } else {
            val first = ((fromMeters - along[i]) / length).coerceIn(0.0, 1.0)
            val last = ((toMeters - along[i]) / length).coerceIn(first, 1.0)
            (-(ax * dx + ay * dy) / squared).coerceIn(first, last)
        }
        return LinePosition(
            segment = i,
            alongMeters = along[i] + length * t,
            offMeters = hypot(ax + dx * t, ay + dy * t),
            point = between(a, b, t),
        )
    }

    private fun between(a: LatLng, b: LatLng, t: Double) =
        LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)

    private companion object {
        const val SAME_PASS_SLACK_METERS = 5.0
    }
}

/**
 * What is known after a fix.
 *
 * @property remainingSeconds the route's own duration, scaled by how much of its length is
 * left. An estimate from the route, not a measurement of how fast the person moves.
 * @property travelled the line from the start of the route to where the person is on it.
 */
data class RouteProgress(
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val offRoute: Boolean,
    val arrived: Boolean,
    val travelled: List<LatLng>,
) {
    override fun toString(): String = "RouteProgress(hidden)"
}

/**
 * Follows one person along one route, fix by fix.
 *
 * - **Forward only.** A fix is matched against the stretch of the line just behind and ahead of
 *   the last known place on it, never the whole line. The stretch ahead grows with the time
 *   since the last match, so a gap in GPS is caught up with.
 * - **Never backwards.** GPS jitters; the travelled part only grows.
 * - **Poor fixes decide nothing.** A fix that is not accurate to [MAX_ACCURACY_METERS] changes
 *   neither progress nor "off the route" nor "arrived".
 * - **Off the route** means farther from the line than `max(50 m, 2 x accuracy)` for
 *   [OFF_ROUTE_AFTER_MILLIS] without a break. One fix back near the line ends it.
 * - **Arrived** means within `max(30 m, accuracy)` of the end of the route.
 *
 * Times are the fixes' own ([LocationFix.timeMillis]); this class reads no clock.
 */
class RouteTracker(private val route: RouteOption, startMillis: Long) {

    private val geometry = RouteGeometry(route.points)
    private var travelledMeters = 0.0
    private var segment = 0
    private var lastMatchMillis = startMillis
    private var offSinceMillis: Long? = null

    var progress: RouteProgress = snapshot(offRoute = false, arrived = false)
        private set

    fun onFix(fix: LocationFix): RouteProgress {
        if (fix.accuracyMeters > MAX_ACCURACY_METERS || route.points.size < 2) return progress
        val waited = (fix.timeMillis - lastMatchMillis).coerceAtLeast(0) / 1000.0
        val found = geometry.locate(
            position = fix.position,
            fromMeters = travelledMeters - BACK_TOLERANCE_METERS,
            toMeters = travelledMeters + WINDOW_METERS + MAX_SPEED_METERS_PER_SECOND * waited,
        )
        if (found != null && found.offMeters <= max(OFF_ROUTE_METERS, 2.0 * fix.accuracyMeters)) {
            if (found.alongMeters > travelledMeters) {
                travelledMeters = found.alongMeters
                segment = found.segment
            }
            lastMatchMillis = fix.timeMillis
            offSinceMillis = null
        } else if (offSinceMillis == null) {
            offSinceMillis = fix.timeMillis
        }
        val offSince = offSinceMillis
        progress = snapshot(
            offRoute = offSince != null && fix.timeMillis - offSince >= OFF_ROUTE_AFTER_MILLIS,
            arrived = metersBetween(fix.position, route.points.last()) <= max(ARRIVAL_METERS, fix.accuracyMeters.toDouble()),
        )
        return progress
    }

    /** The app was away: the time spent off the line is counted from scratch. */
    fun onResume() {
        offSinceMillis = null
        progress = progress.copy(offRoute = false)
    }

    private fun snapshot(offRoute: Boolean, arrived: Boolean): RouteProgress {
        val length = geometry.lengthMeters
        val left = if (length > 0) ((length - travelledMeters) / length).coerceIn(0.0, 1.0) else 0.0
        return RouteProgress(
            remainingMeters = (route.distanceMeters * left).roundToInt(),
            remainingSeconds = (route.durationSeconds * left).roundToInt(),
            offRoute = offRoute,
            arrived = arrived,
            travelled = geometry.lineUpTo(segment, travelledMeters),
        )
    }

    companion object {
        /** A fix less accurate than this decides nothing. */
        const val MAX_ACCURACY_METERS = 50f
        const val OFF_ROUTE_METERS = 50.0
        const val OFF_ROUTE_AFTER_MILLIS = 10_000L
        const val ARRIVAL_METERS = 30.0

        /** How far behind the last known place a fix may still be matched (GPS jitter). */
        const val BACK_TOLERANCE_METERS = 30.0

        /** How far ahead a fix is looked for, plus [MAX_SPEED_METERS_PER_SECOND] per second waited. */
        const val WINDOW_METERS = 50.0
        const val MAX_SPEED_METERS_PER_SECOND = 35.0
    }
}
