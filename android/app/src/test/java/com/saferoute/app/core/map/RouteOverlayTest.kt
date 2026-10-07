// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.core.map

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.feature.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Routes on the map, as descriptions: what is drawn, in which order and colour, and when the
 * camera moves to show a route whole. No map library is involved (ADR 0015); coordinates are
 * round invented values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteOverlayTest {

    private val fast = RouteLine("a", listOf(LatLng(10.0, 20.0), LatLng(10.25, 20.25), LatLng(10.5, 20.5)))
    private val other = RouteLine("b", listOf(LatLng(10.0, 20.0), LatLng(10.6, 19.9), LatLng(10.5, 20.5)))
    private val start = LatLng(10.0, 20.0)
    private val palette = OverlayPalette(
        location = "#0000FF",
        stale = "#808080",
        halo = "#FFFFFF",
        line = "#0000FF",
        place = "#800080",
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `alternatives are drawn first, the selected route on top, then the start ring`() {
        val overlays = routeOverlays(ShownRoutes(listOf(fast, other), selectedId = "a", start = start, fitToken = 1))
        assertEquals(
            listOf(
                MapOverlay.Route("route-b", other.points, selected = false),
                MapOverlay.Route("route-a", fast.points, selected = true),
                MapOverlay.Marker("route-start", start, style = MarkerStyle.RouteStart),
            ),
            overlays,
        )
        // Choosing the other one swaps the order and the emphasis; ids stay the same.
        val swapped = routeOverlays(ShownRoutes(listOf(fast, other), selectedId = "b", start = start, fitToken = 1))
        assertEquals(listOf("route-a", "route-b", "route-start"), swapped.map { it.id })
        assertTrue((swapped[1] as MapOverlay.Route).selected)
    }

    @Test
    fun `a route is a light casing with a line on it - strong and wide when selected, grey and thin otherwise`() {
        val selected = overlaysToGeoJson(listOf(MapOverlay.Route("r", fast.points, selected = true)), palette)
        val muted = overlaysToGeoJson(listOf(MapOverlay.Route("r", fast.points, selected = false)), palette)

        assertTrue(""""kind":"${OverlayKind.ROUTE_CASING}","color":"#FFFFFF"""" in selected)
        assertTrue(""""kind":"${OverlayKind.ROUTE}","color":"#0000FF"""" in selected)
        assertTrue(""""width":6.0000000""" in selected)
        assertTrue(""""width":9.0000000""" in selected)
        assertTrue(""""kind":"${OverlayKind.ROUTE}","color":"#808080"""" in muted)
        assertTrue(""""width":4.0000000""" in muted)
        // The casing comes before the line, so the line is painted on it. Longitude first.
        assertTrue(selected.indexOf(OverlayKind.ROUTE_CASING) < selected.lastIndexOf(""""kind":"route""""))
        assertTrue("[[20.0000000,10.0000000],[20.2500000,10.2500000],[20.5000000,10.5000000]]" in selected)
        // Never the emergency red, never a "safe" green: only the palette's line, grey and white.
        assertEquals(setOf("#FFFFFF", "#0000FF", "#808080"), Regex("#[0-9A-F]{6}").findAll(selected + muted).map { it.value }.toSet())
    }

    @Test
    fun `the start of a route is a ring in the route's colour, not a filled dot or a pin`() {
        val json = overlaysToGeoJson(listOf(MapOverlay.Marker("s", start, style = MarkerStyle.RouteStart)), palette)
        assertTrue(""""kind":"${OverlayKind.DOT}"""" in json)
        assertTrue(""""opacity":0.0000000""" in json)
        assertTrue(""""stroke":"#0000FF"""" in json)
        assertFalse(""""kind":"${OverlayKind.PIN}"""" in json)
    }

    @Test
    fun `the box around a line, and nothing printable about routes`() {
        assertEquals(LatLngBounds(LatLng(10.0, 19.9), LatLng(10.6, 20.5)), boundsOf(other.points))
        assertNull(boundsOf(emptyList()))
        val printed = listOf(
            fast,
            ShownRoutes(listOf(fast), "a", start, 1),
            boundsOf(fast.points),
            MapOverlay.Route("r", fast.points, true),
        ).joinToString(" ")
        listOf("10.", "20.", "19.9").forEach { assertFalse("toString contains $it", it in printed) }
    }

    @Test
    fun `the map view is asked to fit bounds, and only while there is one`() = runTest {
        val holder = MapStateHolder(MapProviderConfig(MapKey(FAKE_MAP_KEY)), FakeNetworkStatus(), backgroundScope)
        val bounds = boundsOf(fast.points)!!
        holder.fitBounds(bounds, animate = true)
        val renderer = FakeMapRenderer()
        holder.attach(renderer)
        assertTrue(renderer.fits.isEmpty())
        holder.fitBounds(bounds, animate = true)
        assertEquals(listOf(bounds), renderer.fits)
    }

    // --- Home draws what the directions feature shows ---------------------------------------

    private val engine = FakeMapEngine()
    private val location = FakeLocationRepository()
    private val selection = MapSelection()
    private val display = RouteDisplay()
    private val map get() = engine.controller

    private fun TestScope.home(): HomeViewModel =
        HomeViewModel(SavedStateHandle(), engine, location, selection, display).also { runCurrent() }

    @Test
    fun `new routes are drawn under the dot and the pin, and the camera shows the selected one whole, once`() = runTest {
        val viewModel = home()
        location.state.value = LocationState.Fix(fakeFix(position = start))
        viewModel.onLocationAvailable(userAsked = false)
        selection.select(SelectedPlace("Main Station", "Example", LatLng(10.5, 20.5)))
        val movesBefore = map.cameraMoves.size

        display.show(listOf(fast, other), selectedId = "a", start = start)
        runCurrent()
        val ids = map.overlays.map { it.id }
        assertEquals(listOf("route-b", "route-a", "route-start"), ids.take(3))
        assertEquals("selected-place", ids.last())
        assertTrue("the location dot is above the routes", ids.size > 4)
        assertEquals(listOf(boundsOf(fast.points)), map.fits)
        // Fitting is not a "fly to": the padding set for the sheet and the pill stays in force.
        assertEquals(movesBefore, map.cameraMoves.size)

        // Another alternative: redrawn with the other emphasis, and the map stays put.
        display.select("b")
        runCurrent()
        assertEquals(listOf("route-a", "route-b"), map.overlays.map { it.id }.take(2))
        assertEquals(1, map.fits.size)

        // A new answer (Try again, another mode) fits again.
        display.show(listOf(other), selectedId = "b", start = start)
        runCurrent()
        assertEquals(listOf(boundsOf(fast.points), boundsOf(other.points)), map.fits)

        display.clear()
        runCurrent()
        assertTrue(map.overlays.none { it is MapOverlay.Route || it.id == "route-start" })
        assertEquals("selected-place", map.overlays.last().id)
    }

    @Test
    fun `showing a route stops the map from following the position`() = runTest {
        val viewModel = home()
        location.state.value = LocationState.Fix(fakeFix(position = start))
        viewModel.onLocationAvailable(userAsked = true)
        runCurrent()
        // Centred on the position: the next tap starts following.
        viewModel.onMyLocationClick()
        runCurrent()

        display.show(listOf(fast), selectedId = "a", start = start)
        runCurrent()
        val moves = map.cameraMoves.size
        location.state.value = LocationState.Fix(fakeFix(position = LatLng(10.1, 20.1)))
        runCurrent()
        assertEquals("the camera stays on the route", moves, map.cameraMoves.size)
    }

    @Test
    fun `a map failure does not stop routes from being described`() = runTest {
        home()
        map.loadState.value = MapLoadState.Error
        display.show(listOf(fast), selectedId = "a", start = start)
        runCurrent()
        assertTrue(map.overlays.any { it is MapOverlay.Route })
    }
}
