// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.location.FAKE_POSITION
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MarkerStyle
import com.saferoute.app.core.map.OverlayPalette
import com.saferoute.app.core.map.RegionDefaults
import com.saferoute.app.core.map.circleRing
import com.saferoute.app.core.map.overlaysToGeoJson
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Location on the map, without a map or a phone: what is drawn for each location state, when
 * the camera moves, and what the "my location" button shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLocationTest {

    private val mapEngine = FakeMapEngine()
    private val location = FakeLocationRepository()
    private lateinit var viewModel: HomeViewModel
    private val map get() = mapEngine.controller

    private val palette = OverlayPalette(place = "#800080", location = "#0000FF", stale = "#888888", halo = "#FFFFFF", line = "#0000FF")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = HomeViewModel(SavedStateHandle(), mapEngine, location, MapSelection(), RouteDisplay())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fix(position: LatLng = FAKE_POSITION) = LocationState.Fix(fakeFix(position))

    /** Great-circle distance in metres. */
    private fun distance(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * 6_371_008.8 * asin(sqrt(h))
    }

    // --- what is drawn ------------------------------------------------------------------

    @Test
    fun `nothing is drawn without a position`() {
        listOf(LocationState.NoPermission, LocationState.Searching, LocationState.Unavailable)
            .forEach { assertEquals(emptyList<MapOverlay>(), locationOverlays(it)) }
    }

    @Test
    fun `a precise fix is a dot with its heading on a circle as wide as the accuracy`() {
        val overlays = locationOverlays(
            LocationState.Fix(fakeFix(accuracyMeters = 25f, headingDegrees = 90f)),
        )

        val circle = overlays[0] as MapOverlay.AccuracyCircle
        val marker = overlays[1] as MapOverlay.Marker
        assertEquals(25.0, circle.radiusMeters, 0.0)
        assertEquals(FAKE_POSITION, circle.center)
        assertEquals(MarkerStyle.Default, marker.style)
        assertEquals(90.0, marker.headingDegrees!!, 0.0)
    }

    @Test
    fun `approximate and stale positions have their own styles`() {
        val approximate = locationOverlays(
            LocationState.Fix(fakeFix(accuracyMeters = 2_000f, isApproximate = true)),
        )
        val stale = locationOverlays(LocationState.Stale(fakeFix(), ageSeconds = 90))

        assertTrue(approximate.all { (it as? MapOverlay.Marker)?.style ?: (it as MapOverlay.AccuracyCircle).style == MarkerStyle.Approximate })
        assertTrue(stale.all { (it as? MapOverlay.Marker)?.style ?: (it as MapOverlay.AccuracyCircle).style == MarkerStyle.Stale })
    }

    @Test
    fun `the accuracy circle is a closed ring at the right distance all the way round`() {
        val ring = circleRing(FAKE_POSITION, radiusMeters = 150.0)

        assertEquals(ring.first(), ring.last())
        assertTrue(ring.size > 24)
        ring.forEach { assertEquals(150.0, distance(FAKE_POSITION, it), 0.5) }
    }

    @Test
    fun `geojson carries shape, colour and style, longitude first`() {
        val precise = overlaysToGeoJson(
            locationOverlays(LocationState.Fix(fakeFix(headingDegrees = 45f))),
            palette,
        )
        assertTrue(precise.startsWith("""{"type":"FeatureCollection""""))
        assertTrue(precise.contains(""""type":"Point","coordinates":[20.0000000,10.0000000]"""))
        assertTrue(precise.contains(""""kind":"dot","color":"#0000FF","opacity":1.0000000,"stroke":"#FFFFFF""""))
        assertTrue(precise.contains(""""kind":"heading""""))
        assertTrue(precise.contains(""""heading":45.0000000"""))
        assertTrue(precise.contains(""""kind":"fill""""))

        // Approximate: a ring instead of a dot (not only a different colour), and no arrow.
        val approximate = overlaysToGeoJson(
            locationOverlays(LocationState.Fix(fakeFix(isApproximate = true, headingDegrees = 45f))),
            palette,
        )
        assertTrue(approximate.contains(""""kind":"dot","color":"#0000FF","opacity":0.0000000,"stroke":"#0000FF""""))
        assertFalse(approximate.contains("heading"))

        // Stale: grey, and no arrow.
        val stale = overlaysToGeoJson(
            locationOverlays(LocationState.Stale(fakeFix(headingDegrees = 45f), 60)),
            palette,
        )
        assertTrue(stale.contains(""""kind":"dot","color":"#888888""""))
        assertFalse(stale.contains("heading"))
        assertFalse(stale.contains("#0000FF"))
    }

    @Test
    fun `lines and areas are described too, and an empty list is valid geojson`() {
        val json = overlaysToGeoJson(
            listOf(
                MapOverlay.Polyline("route", listOf(LatLng(1.0, 2.0), LatLng(3.0, 4.0))),
                MapOverlay.Polygons("area", listOf(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0), LatLng(0.0, 0.0)))),
            ),
            palette,
        )

        assertTrue(json.contains(""""type":"LineString","coordinates":[[2.0000000,1.0000000],[4.0000000,3.0000000]]"""))
        assertTrue(json.contains(""""type":"Polygon""""))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", overlaysToGeoJson(emptyList(), palette))
    }

    // --- the button -----------------------------------------------------------------------

    @Test
    fun `the button follows permission, position and follow mode`() {
        assertEquals(MyLocationControl.Off, myLocationControl(false, fix(), following = true))
        assertEquals(MyLocationControl.Off, myLocationControl(true, LocationState.NoPermission, false))
        assertEquals(MyLocationControl.Searching, myLocationControl(true, LocationState.Searching, false))
        assertEquals(MyLocationControl.Unavailable, myLocationControl(true, LocationState.Unavailable, true))
        assertEquals(MyLocationControl.Located, myLocationControl(true, fix(), false))
        assertEquals(MyLocationControl.Located, myLocationControl(true, LocationState.Stale(fakeFix(), 60), false))
        assertEquals(MyLocationControl.Following, myLocationControl(true, fix(), true))
    }

    // --- the camera -------------------------------------------------------------------------

    @Test
    fun `location starts only when told and stops when told`() {
        assertEquals(0, location.starts)
        assertEquals(MyLocationControl.Off, viewModel.myLocation.value)

        viewModel.onLocationAvailable(userAsked = false)
        assertTrue(location.running)
        assertEquals(MyLocationControl.Searching, viewModel.myLocation.value)

        viewModel.onLocationUnavailable(permissionLost = false)
        assertFalse(location.running)
    }

    @Test
    fun `the first fix after a tap centres the map once, at street zoom`() {
        viewModel.onLocationAvailable(userAsked = true)

        location.state.value = fix()

        assertEquals(listOf(CameraState(FAKE_POSITION, LOCATE_ZOOM)), map.cameraMoves)
        assertEquals(2, map.overlays.size)

        // Later fixes move the dot, not the camera.
        val next = LatLng(10.01, 20.01)
        location.state.value = fix(next)
        assertEquals(1, map.cameraMoves.size)
        assertEquals(next, (map.overlays[1] as MapOverlay.Marker).position)
    }

    @Test
    fun `a fix nobody asked for moves only the dot`() {
        viewModel.onLocationAvailable(userAsked = false)

        location.state.value = fix()

        assertTrue(map.cameraMoves.isEmpty())
        assertEquals(RegionDefaults.camera, map.camera.value)
        assertEquals(2, map.overlays.size)
        assertEquals(MyLocationControl.Located, viewModel.myLocation.value)
    }

    @Test
    fun `tap centres, a second tap follows, a third stops following`() {
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix()

        assertTrue(viewModel.onMyLocationClick())
        assertEquals(FAKE_POSITION, map.camera.value.target)
        assertEquals(MyLocationControl.Located, viewModel.myLocation.value)

        viewModel.onMyLocationClick()
        assertEquals(MyLocationControl.Following, viewModel.myLocation.value)

        // Following: the camera goes where the position goes, keeping zoom and bearing.
        val next = LatLng(10.5, 20.5)
        location.state.value = fix(next)
        assertEquals(next, map.camera.value.target)
        assertEquals(LOCATE_ZOOM, map.camera.value.zoom, 0.0)

        viewModel.onMyLocationClick()
        assertEquals(MyLocationControl.Located, viewModel.myLocation.value)
        val moves = map.cameraMoves.size
        location.state.value = fix(LatLng(10.6, 20.6))
        assertEquals(moves, map.cameraMoves.size)
    }

    @Test
    fun `centring keeps a closer zoom the user already chose`() {
        map.camera.value = RegionDefaults.camera.copy(zoom = 18.0)
        viewModel.onLocationAvailable(userAsked = true)

        location.state.value = fix()

        assertEquals(18.0, map.cameraMoves.single().zoom, 0.0)
    }

    @Test
    fun `dragging the map away ends follow mode`() {
        viewModel.onLocationAvailable(userAsked = true)
        location.state.value = fix()
        viewModel.onMyLocationClick()
        assertEquals(MyLocationControl.Following, viewModel.myLocation.value)

        // What the map reports when the user's finger has moved it.
        map.camera.value = map.camera.value.copy(target = LatLng(11.0, 21.0))

        assertEquals(MyLocationControl.Located, viewModel.myLocation.value)
    }

    @Test
    fun `a tap while searching centres on the fix when it comes`() {
        viewModel.onLocationAvailable(userAsked = false)

        assertTrue(viewModel.onMyLocationClick())
        assertTrue(map.cameraMoves.isEmpty())
        location.state.value = fix()

        assertEquals(FAKE_POSITION, map.cameraMoves.single().target)
    }

    @Test
    fun `a tap with no position possible tries again and reports failure`() {
        location.stateOnStart = LocationState.Unavailable
        viewModel.onLocationAvailable(userAsked = true)
        assertEquals(MyLocationControl.Unavailable, viewModel.myLocation.value)

        assertFalse(viewModel.onMyLocationClick())
        assertEquals(2, location.starts)

        // Location was switched on in the meantime: the retry works.
        location.stateOnStart = LocationState.Searching
        assertTrue(viewModel.onMyLocationClick())
    }

    @Test
    fun `a stale position greys the dot and does not move the camera`() {
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix()
        viewModel.onMyLocationClick()
        viewModel.onMyLocationClick()
        val moves = map.cameraMoves.size

        location.state.value = LocationState.Stale(fakeFix(), ageSeconds = 45)

        assertEquals(MarkerStyle.Stale, (map.overlays[1] as MapOverlay.Marker).style)
        assertEquals(moves, map.cameraMoves.size)
    }

    @Test
    fun `losing the permission takes the dot off the map and stops everything`() {
        viewModel.onLocationAvailable(userAsked = true)
        location.state.value = fix()
        viewModel.onMyLocationClick()

        viewModel.onLocationUnavailable(permissionLost = true)

        assertFalse(location.running)
        assertTrue(map.overlays.isEmpty())
        assertEquals(MyLocationControl.Off, viewModel.myLocation.value)
        // A position that still arrives is not drawn.
        location.state.value = fix(LatLng(12.0, 22.0))
        assertTrue(map.overlays.isEmpty())
    }

    @Test
    fun `going to the background keeps the dot but stops updates`() {
        viewModel.onLocationAvailable(userAsked = true)
        location.state.value = fix()

        viewModel.onLocationUnavailable(permissionLost = false)

        assertFalse(location.running)
        assertEquals(2, map.overlays.size)
    }

    @Test
    fun `positions are not put in the saved state`() {
        val savedState = SavedStateHandle()
        val engine = FakeMapEngine()
        val vm = HomeViewModel(savedState, engine, location, MapSelection(), RouteDisplay())
        vm.onLocationAvailable(userAsked = false)

        location.state.value = fix(LatLng(12.345678, 98.765432))

        // Only the camera is saved, and nothing asked the camera to move.
        assertEquals(setOf("map_camera"), savedState.keys())
        val camera = savedState.get<DoubleArray>("map_camera")!!
        assertEquals(RegionDefaults.camera.target.latitude, camera[0], 0.0)
        assertNull(camera.firstOrNull { it == 12.345678 || it == 98.765432 })
    }

    @Test
    fun `location trouble does not touch the emergency dialog`() {
        viewModel.onEmergencyClick()
        location.stateOnStart = LocationState.Unavailable
        viewModel.onLocationAvailable(userAsked = true)
        viewModel.onMyLocationClick()

        assertEquals(EmergencyDialogState.OfferDialer, viewModel.emergencyDialog.value)
    }
}
