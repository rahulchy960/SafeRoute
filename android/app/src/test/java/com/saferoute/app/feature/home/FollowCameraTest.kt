// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.FollowView
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.RouteLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The map while a route is followed (ADR 0022): the camera stays on the user and turns with
 * them until they move the map, "Re-centre" brings it back, and only the followed route is
 * drawn, with the part behind the user muted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FollowCameraTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val engine = FakeMapEngine()
    private val display = RouteDisplay()
    private val map get() = engine.controller
    private lateinit var viewModel: HomeViewModel

    private val a = LatLng(10.0, 20.0)
    private val b = LatLng(10.0, 20.01)
    private val c = LatLng(10.01, 20.01)
    private val lines = listOf(RouteLine("one", listOf(a, b, c)), RouteLine("two", listOf(a, c)))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = HomeViewModel(SavedStateHandle(), engine, FakeLocationRepository(), MapSelection(), display)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.routesShown() {
        runCurrent()
        display.show(lines, "one", a)
        runCurrent()
    }

    private fun TestScope.follow(view: FollowView?) {
        display.follow(view)
        runCurrent()
    }

    @Test
    fun `the camera goes to the user at street level and turns with the direction of travel`() = scope.runTest {
        routesShown()
        follow(FollowView(listOf(a), a, bearing = 90.0))
        assertEquals(CameraState(a, zoom = 17.0, bearing = 90.0), map.cameraMoves.last())
        assertFalse(viewModel.recentreOffered.value)

        follow(FollowView(listOf(a, b), b, bearing = 0.0))
        assertEquals(CameraState(b, zoom = 17.0, bearing = 0.0), map.cameraMoves.last())
    }

    @Test
    fun `after a pan the camera is left alone until Re-centre`() = scope.runTest {
        routesShown()
        follow(FollowView(listOf(a), a, bearing = 90.0))
        val moves = map.cameraMoves.size
        map.userMoves(CameraState(c, zoom = 12.0))
        runCurrent()
        assertTrue(viewModel.recentreOffered.value)
        follow(FollowView(listOf(a, b), b, bearing = 45.0))
        assertEquals(moves, map.cameraMoves.size)

        viewModel.onRecentre()
        assertFalse(viewModel.recentreOffered.value)
        // Back on the user, close up again, facing the way they travel.
        assertEquals(CameraState(b, zoom = 17.0, bearing = 45.0), map.cameraMoves.last())
    }

    @Test
    fun `only the followed route is drawn, with the part behind the user muted on top`() = scope.runTest {
        routesShown()
        assertEquals(2, map.overlays.filterIsInstance<MapOverlay.Route>().size)

        follow(FollowView(listOf(a, b), b, bearing = 0.0))
        val drawn = map.overlays.filterIsInstance<MapOverlay.Route>()
        assertEquals(listOf("route-one", "route-travelled"), drawn.map { it.id })
        assertEquals(listOf(true, false), drawn.map { it.selected })
        assertEquals(listOf(false, true), drawn.map { it.travelled })
        assertEquals(listOf(a, b), drawn.last().points)
        // No ring where the route began: it is behind the user now.
        assertTrue(map.overlays.none { it.id == "route-start" })
    }

    @Test
    fun `a recalculated route does not pull the camera off the user`() = scope.runTest {
        routesShown()
        assertEquals(1, map.fits.size)
        follow(FollowView(listOf(a), a, bearing = 0.0))
        display.show(listOf(RouteLine("new", listOf(b, c))), "new", b)
        runCurrent()
        assertEquals(1, map.fits.size)
    }

    @Test
    fun `when following ends north is up again, the alternatives are back and Re-centre is gone`() = scope.runTest {
        routesShown()
        follow(FollowView(listOf(a), a, bearing = 90.0))
        map.userMoves(CameraState(c, zoom = 12.0, bearing = 30.0))
        runCurrent()
        follow(null)
        assertEquals(CameraState(c, zoom = 12.0, bearing = 0.0), map.cameraMoves.last())
        assertFalse(viewModel.recentreOffered.value)
        assertEquals(2, map.overlays.filterIsInstance<MapOverlay.Route>().size)
        assertTrue(map.overlays.none { (it as? MapOverlay.Route)?.travelled == true })
    }
}
