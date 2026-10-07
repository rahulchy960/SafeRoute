// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.map.LatLng
import kotlin.math.abs

private const val PRECISION = 1e6
private const val CHAR_OFFSET = 63
private const val CONTINUE_BIT = 0x20
private const val VALUE_BITS = 0x1F

/** No coordinate needs more than seven 5-bit groups; more means the text is not a polyline. */
private const val MAX_GROUPS = 7

/**
 * Decodes a "polyline6" string, the compact text form the server sends a route's line in.
 *
 * How it is packed: each number is the *difference* to the previous latitude or longitude,
 * multiplied by 1 000 000 and rounded. The sign is moved into the lowest bit, the result is cut
 * into 5-bit groups from the low end, 63 is added to each group to make a printable character,
 * and every group but the last of a number has bit 5 set ("more follows"). Differences are
 * small, so a long route is a few kilobytes.
 *
 * Returns null when the text is not a complete, valid polyline: a broken route is not drawn.
 * An empty string is an empty line. Run it off the main thread: a statewide route has tens of
 * thousands of points.
 */
internal fun decodePolyline6(encoded: String): List<LatLng>? {
    val points = ArrayList<LatLng>(encoded.length / 4)
    var index = 0
    var latitude = 0L
    var longitude = 0L

    fun next(): Long? {
        var result = 0L
        var shift = 0
        var groups = 0
        while (true) {
            if (index >= encoded.length || ++groups > MAX_GROUPS) return null
            val group = encoded[index++].code - CHAR_OFFSET
            if (group !in 0..CHAR_OFFSET) return null
            result = result or ((group and VALUE_BITS).toLong() shl shift)
            shift += 5
            if (group < CONTINUE_BIT) break
        }
        return if (result and 1L == 1L) (result shr 1).inv() else result shr 1
    }

    while (index < encoded.length) {
        latitude += next() ?: return null
        longitude += next() ?: return null
        val lat = latitude / PRECISION
        val lng = longitude / PRECISION
        if (abs(lat) > 90 || abs(lng) > 180) return null
        points += LatLng(lat, lng)
    }
    return points
}
