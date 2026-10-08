// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.location.LocationFix
import com.saferoute.app.core.map.LatLng
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteTracker] and its geometry: where a fix falls on a line, and what follows from it. Plain
 * JVM tests; the lines are drawn in metres on a flat sheet next to a round, invented point.
 */
class RouteTrackerTest {

    /** A point [east] and [north] metres from the invented origin. */
    private fun at(east: Double, north: Double) = LatLng(
        latitude = 10.0 + north / 111_320.0,
        longitude = 20.0 + east / (111_320.0 * cos(Math.toRadians(10.0))),
    )

    private fun route(vararg corners: Pair<Int, Int>, seconds: Int = 1000): RouteOption {
        val points = corners.map { (east, north) -> at(east.toDouble(), north.toDouble()) }
        val meters = points.zipWithNext { a, b -> metersBetween(a, b) }.sum()
        return RouteOption("r", meters.toInt(), seconds, points)
    }

    private var now = 0L

    /** One fix per second, as the phone gives them while it follows. */
    private fun RouteTracker.fix(east: Number, north: Number, accuracy: Float = 10f): RouteProgress {
        now += 1000
        return onFix(LocationFix(at(east.toDouble(), north.toDouble()), accuracy, now, isApproximate = false))
    }

    private val straight = route(0 to 0, 1000 to 0)

    @Test
    fun `a straight line - the travelled part is how far along the fix falls`() {
        val tracker = RouteTracker(straight, 0)
        assertEquals(1000, tracker.progress.remainingMeters)
        // 20 m beside the line counts as on it.
        val progress = tracker.fix(40, 20)
        assertEquals(960.0, progress.remainingMeters.toDouble(), 2.0)
        assertFalse(progress.offRoute)
        // The travelled line ends on the route, not at the fix beside it.
        assertEquals(0.0, metersBetween(progress.travelled.last(), at(40.0, 0.0)), 0.5)
    }

    @Test
    fun `remaining time is the route's duration scaled by the length left`() {
        val tracker = RouteTracker(straight, 0)
        assertEquals(1000, tracker.progress.remainingSeconds)
        tracker.fix(50, 0)
        tracker.fix(100, 0)
        val quarter = (1..3).map { tracker.fix(100 + it * 50, 0) }.last()
        assertEquals(750.0, quarter.remainingMeters.toDouble(), 2.0)
        assertEquals(750.0, quarter.remainingSeconds.toDouble(), 2.0)
    }

    @Test
    fun `around bends the length is measured along the line`() {
        val tracker = RouteTracker(route(0 to 0, 100 to 0, 100 to 100, 200 to 100), 0)
        tracker.fix(60, 0)
        tracker.fix(100, 5)
        val progress = tracker.fix(102, 60)
        assertEquals(300 - 160.0, progress.remainingMeters.toDouble(), 2.0)
        // The start, the first corner, and the point reached on the second leg.
        assertEquals(3, progress.travelled.size)
    }

    @Test
    fun `GPS jitter never moves progress backwards`() {
        val tracker = RouteTracker(straight, 0)
        var last = 1000
        listOf(40, 80, 70, 65, 90, 85, 120, 110).forEach { east ->
            val remaining = tracker.fix(east, 3).remainingMeters
            assertTrue("went back at $east", remaining <= last)
            last = remaining
        }
        assertEquals(880.0, last.toDouble(), 2.0)
    }

    @Test
    fun `a single jump far away changes nothing and is forgotten with the next fix`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(40, 0)
        // Far ahead along the line: outside the stretch a second of travel can reach.
        val jumped = tracker.fix(700, 0)
        assertEquals(960.0, jumped.remainingMeters.toDouble(), 2.0)
        assertFalse(jumped.offRoute)
        val back = tracker.fix(80, 0)
        assertEquals(920.0, back.remainingMeters.toDouble(), 2.0)
        assertFalse(back.offRoute)
    }

    @Test
    fun `a loop - the crossing does not jump progress to the later pass`() {
        // East, north, west, then south across the first leg at (50, 0).
        val loop = route(0 to 0, 100 to 0, 100 to 100, 50 to 100, 50 to -50)
        val tracker = RouteTracker(loop, 0)
        tracker.fix(25, 0)
        val atCrossing = tracker.fix(50, 0)
        assertEquals(50.0, 400 - atCrossing.remainingMeters.toDouble(), 2.0)
        // Round the loop and through the same point again: now it is the later pass.
        listOf(90 to 0, 100 to 40, 100 to 90, 60 to 100, 50 to 60, 50 to 20).forEach { (e, n) -> tracker.fix(e, n) }
        val again = tracker.fix(50, 0)
        assertEquals(350.0, 400 - again.remainingMeters.toDouble(), 2.0)
    }

    @Test
    fun `two legs close together - the nearer later one does not win before its time`() {
        // Out along one street and back along the next, 20 m apart.
        val tracker = RouteTracker(route(0 to 0, 300 to 0, 300 to 20, 0 to 20), 0)
        // Walking the first leg, but GPS puts the person nearer to the second.
        val progress = listOf(30, 60, 90).map { tracker.fix(it, 14) }.last()
        assertEquals(90.0, 620 - progress.remainingMeters.toDouble(), 2.0)
    }

    @Test
    fun `out and back on the same road - progress reaches the end and never goes back`() {
        val tracker = RouteTracker(route(0 to 0, 200 to 0, 0 to 0), 0)
        var last = 400
        val positions = (1..10).map { it * 20 } + (9 downTo 1).map { it * 20 }
        positions.forEach { east ->
            val remaining = tracker.fix(east, 0).remainingMeters
            assertTrue("went back at $east", remaining <= last)
            last = remaining
        }
        assertEquals(20.0, last.toDouble(), 2.0)
    }

    @Test
    fun `off the route needs more than 50 m for ten seconds without a break`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(100, 0)
        // 49 m away is still on the route.
        repeat(15) { assertFalse(tracker.fix(100, 49).offRoute) }
        // 60 m away: not yet after 9 seconds, then yes.
        val first = tracker.fix(100, 60)
        assertFalse(first.offRoute)
        repeat(9) { assertFalse(tracker.fix(100, 60).offRoute) }
        assertTrue(tracker.fix(100, 60).offRoute)
        // Progress did not move while away from the line.
        assertEquals(900.0, tracker.progress.remainingMeters.toDouble(), 2.0)
    }

    @Test
    fun `one fix back on the line ends being off the route, and the ten seconds start again`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(100, 0)
        repeat(11) { tracker.fix(100, 80) }
        assertTrue(tracker.progress.offRoute)
        assertFalse(tracker.fix(110, 5).offRoute)
        repeat(9) { assertFalse(tracker.fix(110, 80).offRoute) }
    }

    @Test
    fun `the limit grows with the fix's own uncertainty - twice its accuracy`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(100, 0)
        // 80 m away with 45 m accuracy: within 2 x 45, so not off the route.
        repeat(15) { assertFalse(tracker.fix(100, 80, accuracy = 45f).offRoute) }
        // The same 80 m with 10 m accuracy is.
        repeat(11) { tracker.fix(100, 80, accuracy = 10f) }
        assertTrue(tracker.progress.offRoute)
    }

    @Test
    fun `a fix less accurate than 50 m decides nothing`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(100, 0)
        val before = tracker.progress
        // Neither off the route, nor progress, nor arrival (it is "at" the end).
        repeat(20) { assertEquals(before, tracker.fix(1000, 300, accuracy = 51f)) }
        // And poor fixes do not clear a banner that good ones raised.
        repeat(11) { tracker.fix(100, 80) }
        assertTrue(tracker.fix(100, 0, accuracy = 80f).offRoute)
    }

    @Test
    fun `a shortcut - rejoining the route farther on is found once time has passed`() {
        val tracker = RouteTracker(route(0 to 0, 0 to 300, 300 to 300, 300 to 0), 0)
        tracker.fix(0, 20)
        // Straight across, slowly, instead of round three sides.
        (1..20).forEach { tracker.fix(it * 10, 10) }
        assertTrue(tracker.progress.offRoute)
        (21..29).forEach { tracker.fix(it * 10, 10) }
        val rejoined = tracker.fix(298, 10)
        assertFalse(rejoined.offRoute)
        assertEquals(10.0, rejoined.remainingMeters.toDouble(), 3.0)
    }

    @Test
    fun `arrived means within 30 m of the end, or within the fix's accuracy`() {
        val tracker = RouteTracker(straight, 0)
        assertFalse(tracker.fix(960, 0).arrived)
        assertTrue(tracker.fix(975, 10).arrived)

        val coarse = RouteTracker(straight, 0)
        assertTrue(coarse.fix(960, 0, accuracy = 45f).arrived)
        // Reaching the place by another way counts too: it is about the place, not the line.
        val other = RouteTracker(straight, 0)
        assertTrue(other.fix(1010, 20).arrived)
    }

    @Test
    fun `after the app was away the time off the route starts from nothing`() {
        val tracker = RouteTracker(straight, 0)
        tracker.fix(100, 0)
        repeat(11) { tracker.fix(100, 80) }
        tracker.onResume()
        assertFalse(tracker.progress.offRoute)
        assertFalse(tracker.fix(100, 80).offRoute)
    }

    @Test
    fun `a route without a line and a point on a point do not crash`() {
        val empty = RouteTracker(RouteOption("e", 0, 0, emptyList()), 0)
        assertEquals(0, empty.fix(0, 0).remainingMeters)
        val dot = RouteTracker(route(0 to 0, 0 to 0, 50 to 0), 0)
        assertEquals(30.0, 50 - dot.fix(30, 0).remainingMeters.toDouble(), 2.0)
    }

    @Test
    fun `nothing here prints a position`() {
        val tracker = RouteTracker(straight, 0)
        val text = tracker.fix(40, 0).toString()
        assertEquals("RouteProgress(hidden)", text)
    }
}
