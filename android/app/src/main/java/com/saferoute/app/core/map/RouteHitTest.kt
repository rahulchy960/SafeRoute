// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import kotlin.math.hypot

/*
 * Which route a tap on the map means. Plain arithmetic on screen pixels: the map component
 * turns the routes' points into pixels and asks here, so this can be tested without a map.
 */

/** A point on the screen, in pixels. */
data class ScreenPoint(val x: Float, val y: Float)

/** A line as it is drawn on the screen right now. */
class ScreenLine(val id: String, val points: List<ScreenPoint>)

/** How close to a route's line a tap must land to mean that route, in dp. */
const val ROUTE_TAP_TOLERANCE_DP = 24

/**
 * The line nearest to [tap], if one is within [tolerancePx]; otherwise null. Where two lines
 * are equally near (they share a road), the one listed later wins: that is the one drawn on
 * top, the selected route.
 */
fun nearestLine(tap: ScreenPoint, lines: List<ScreenLine>, tolerancePx: Float): String? {
    var best: String? = null
    var bestDistance = tolerancePx
    for (line in lines) {
        val distance = distanceToLine(tap, line.points)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = line.id
        }
    }
    return best
}

private fun distanceToLine(tap: ScreenPoint, points: List<ScreenPoint>): Float {
    if (points.size == 1) return hypot(tap.x - points[0].x, tap.y - points[0].y)
    var nearest = Float.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        // How far along the segment the nearest point lies, kept between its two ends.
        val t = if (squared == 0f) 0f else (((tap.x - a.x) * dx + (tap.y - a.y) * dy) / squared).coerceIn(0f, 1f)
        nearest = minOf(nearest, hypot(tap.x - (a.x + dx * t), tap.y - (a.y + dy * t)))
    }
    return nearest
}
