// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which route a tap means ([nearestLine]), in screen pixels, and how a tap travels from the
 * map view to whoever listens. The step before it, turning map points into pixels, is the map
 * library's and can only be checked on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteHitTestTest {

    private fun line(id: String, vararg points: Pair<Int, Int>) =
        ScreenLine(id, points.map { (x, y) -> ScreenPoint(x.toFloat(), y.toFloat()) })

    private fun tap(x: Int, y: Int) = ScreenPoint(x.toFloat(), y.toFloat())

    // Two routes: one along the top, one that bends down and across.
    private val top = line("route-a", 0 to 100, 400 to 100)
    private val bent = line("route-b", 0 to 100, 0 to 300, 400 to 300)
    private val both = listOf(top, bent)

    @Test
    fun `a tap on a line means that route`() {
        assertEquals("route-a", nearestLine(tap(200, 100), both, 48f))
        assertEquals("route-b", nearestLine(tap(200, 300), both, 48f))
        // The upright part of the bent one, between its corners.
        assertEquals("route-b", nearestLine(tap(4, 220), both, 48f))
    }

    @Test
    fun `near enough counts, a little too far does not`() {
        assertEquals("route-a", nearestLine(tap(200, 148), both, 48f))
        assertNull(nearestLine(tap(200, 149), both, 48f))
        // Beyond the end of a line the distance is to its end point, not to the line carried on.
        assertEquals("route-a", nearestLine(tap(430, 100), both, 48f))
        assertNull(nearestLine(tap(460, 100), both, 48f))
    }

    @Test
    fun `between two routes the nearer one wins`() {
        val close = listOf(line("route-a", 0 to 100, 400 to 100), line("route-b", 0 to 140, 400 to 140))
        assertEquals("route-a", nearestLine(tap(200, 115), close, 48f))
        assertEquals("route-b", nearestLine(tap(200, 125), close, 48f))
    }

    @Test
    fun `where routes share a road the one drawn on top wins`() {
        // Both start along the same stretch; the selected route is listed last.
        assertEquals("route-b", nearestLine(tap(0, 100), both, 48f))
        assertEquals("route-a", nearestLine(tap(0, 100), both.reversed(), 48f))
    }

    @Test
    fun `no routes, a single point and a doubled point do not crash`() {
        assertNull(nearestLine(tap(0, 0), emptyList(), 48f))
        assertNull(nearestLine(tap(0, 0), listOf(line("route-e")), 48f))
        assertEquals("route-p", nearestLine(tap(10, 10), listOf(line("route-p", 0 to 0)), 48f))
        assertEquals("route-d", nearestLine(tap(10, 0), listOf(line("route-d", 0 to 0, 0 to 0, 50 to 0)), 48f))
    }

    @Test
    fun `the tolerance is 24 dp`() {
        assertEquals(24, ROUTE_TAP_TOLERANCE_DP)
    }

    @Test
    fun `a route overlay's id gives the route, any other overlay gives nothing`() {
        assertEquals("fake-route-1", routeIdOf("route-fake-route-1"))
        assertNull(routeIdOf("selected-place"))
        val shown = ShownRoutes(listOf(RouteLine("x", listOf(LatLng(1.0, 2.0), LatLng(1.0, 3.0)))), "x", LatLng(1.0, 2.0), 1)
        assertEquals(listOf("x"), routeOverlays(shown).filterIsInstance<MapOverlay.Route>().mapNotNull { routeIdOf(it.id) })
    }

    @Test
    fun `a tap reported by the map view reaches the controller's listeners, and never waits for one`() =
        runTest(UnconfinedTestDispatcher()) {
            val holder = MapStateHolder(MapProviderConfig(MapKey(FAKE_MAP_KEY)), FakeNetworkStatus(), backgroundScope)
            // Nobody listens: dropped, not an error.
            holder.onRouteTap("route-a")
            val heard = mutableListOf<String>()
            backgroundScope.launch { holder.routeTaps.collect { heard += it } }
            holder.onRouteTap("route-b")
            assertEquals(listOf("route-b"), heard)
        }
}
