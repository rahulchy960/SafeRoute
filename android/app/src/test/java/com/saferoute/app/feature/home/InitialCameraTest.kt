// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.location.FAKE_POSITION
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.CameraState
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RegionDefaults
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.SelectedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.shadows.ShadowLog

/**
 * Where the map opens (ADR 0015, "Initial camera"): on the whole region, and once per launch on
 * the user's position when the permission was already granted and a position arrives in time.
 *
 * Time is virtual: `advanceTimeBy` passes the eight seconds instantly and exactly. Positions
 * are round fixture numbers. Runs under Robolectric so that everything written to Logcat is
 * captured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InitialCameraTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val engine = FakeMapEngine()
    private val location = FakeLocationRepository()
    private val savedState = SavedStateHandle()
    private val map get() = engine.controller

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.launchHome(state: SavedStateHandle = savedState): HomeViewModel {
        val viewModel = HomeViewModel(state, engine, location, MapSelection(), RouteDisplay())
        runCurrent()
        return viewModel
    }

    private fun fix(position: LatLng = FAKE_POSITION) = LocationState.Fix(fakeFix(position))

    private fun old(ageSeconds: Long, position: LatLng = FAKE_POSITION) =
        LocationState.Stale(fakeFix(position), ageSeconds)

    private val opened = CameraState(target = FAKE_POSITION, zoom = INITIAL_LOCATE_ZOOM)

    // --- no permission, no position -----------------------------------------------------

    @Test
    fun `without the permission the map shows the whole region and never moves`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationUnavailable(permissionLost = true)
        advanceTimeBy(60_000)

        assertEquals(RegionDefaults.overview, map.camera.value)
        assertTrue(map.cameraMoves.isEmpty())
        // The overview is a state, not a city.
        assertTrue(map.camera.value.zoom < 8.0)
        assertEquals(0, location.starts)
    }

    @Test
    fun `a permission granted later in system Settings does not move the map by itself`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationUnavailable(permissionLost = true)

        // Back from Settings with the permission on: Home starts location, nobody tapped.
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix()
        advanceTimeBy(60_000)

        assertTrue(map.cameraMoves.isEmpty())
    }

    // --- permission already granted -----------------------------------------------------

    @Test
    fun `a recent last known position opens the map there at street level, at once`() = scope.runTest {
        // What the repository reports for a last known position of four minutes ago.
        location.stateOnStart = old(ageSeconds = 240)
        val viewModel = launchHome()

        viewModel.onLocationAvailable(userAsked = false)

        assertEquals(listOf(opened), map.cameraMoves)
        assertEquals(15.0, INITIAL_LOCATE_ZOOM, 0.0)
    }

    @Test
    fun `ten minutes is still recent, a second more is not`() = scope.runTest {
        location.stateOnStart = old(ageSeconds = INITIAL_FIX_MAX_AGE_SECONDS)
        launchHome().onLocationAvailable(userAsked = false)
        assertEquals(1, map.cameraMoves.size)

        val secondEngine = FakeMapEngine()
        val secondLocation = FakeLocationRepository()
        secondLocation.stateOnStart = old(ageSeconds = INITIAL_FIX_MAX_AGE_SECONDS + 1)
        HomeViewModel(SavedStateHandle(), secondEngine, secondLocation, MapSelection(), RouteDisplay())
            .onLocationAvailable(userAsked = false)
        runCurrent()
        assertTrue(secondEngine.controller.cameraMoves.isEmpty())
    }

    @Test
    fun `an old last known position waits for a new one, which opens the map within 8 seconds`() = scope.runTest {
        location.stateOnStart = old(ageSeconds = 3_600, position = LatLng(30.0, 40.0))
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)
        assertTrue(map.cameraMoves.isEmpty())

        advanceTimeBy(INITIAL_FIX_WAIT_MILLIS - 1)
        location.state.value = fix()
        runCurrent()

        assertEquals(listOf(opened), map.cameraMoves)
    }

    @Test
    fun `no position at all - the first one within 8 seconds opens the map`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)
        assertEquals(LocationState.Searching, location.state.value)

        advanceTimeBy(5_000)
        location.state.value = fix()
        runCurrent()

        assertEquals(listOf(opened), map.cameraMoves)
    }

    @Test
    fun `after 8 seconds without a position the map stays on the overview, for good`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)

        advanceTimeBy(INITIAL_FIX_WAIT_MILLIS + 1)
        location.state.value = fix()
        runCurrent()

        assertTrue(map.cameraMoves.isEmpty())
        assertEquals(RegionDefaults.overview, map.camera.value)
        // The position is still shown: the dot is drawn, only the camera is left alone.
        assertEquals(2, map.overlays.size)
    }

    @Test
    fun `when no position can be had the wait ends at once`() = scope.runTest {
        location.stateOnStart = LocationState.Unavailable
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)

        location.state.value = fix()
        runCurrent()

        assertTrue(map.cameraMoves.isEmpty())
    }

    // --- once only ----------------------------------------------------------------------

    @Test
    fun `the map moves once per launch - later positions and later resumes leave it alone`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix()
        runCurrent()
        assertEquals(1, map.cameraMoves.size)

        location.state.value = fix(LatLng(11.0, 21.0))
        // Home goes to the background and comes back.
        viewModel.onLocationUnavailable(permissionLost = false)
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix(LatLng(12.0, 22.0))
        advanceTimeBy(60_000)

        assertEquals(listOf(opened), map.cameraMoves)
    }

    @Test
    fun `a position outside the launch region opens the map there all the same`() = scope.runTest {
        // Far from West Bengal: the map still shows where the user is.
        val elsewhere = LatLng(-30.0, 150.0)
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)

        location.state.value = fix(elsewhere)
        runCurrent()

        assertEquals(elsewhere, map.cameraMoves.single().target)
    }

    // --- never fight the user -----------------------------------------------------------

    @Test
    fun `a pan, zoom or rotation before the position arrives keeps the camera where the user put it`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)

        val theirs = CameraState(target = LatLng(13.0, 23.0), zoom = 9.5, bearing = 40.0)
        map.userMoves(theirs)
        runCurrent()
        location.state.value = fix()
        advanceTimeBy(INITIAL_FIX_WAIT_MILLIS)

        assertTrue(map.cameraMoves.isEmpty())
        assertEquals(theirs, map.camera.value)
    }

    @Test
    fun `a tap on my location is the user's move - there is no second, automatic one`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationUnavailable(permissionLost = true)

        viewModel.onLocationAvailable(userAsked = true)
        location.state.value = fix()
        runCurrent()

        // The existing behaviour of the button: centred at its own zoom, once.
        assertEquals(LOCATE_ZOOM, map.cameraMoves.single().zoom, 0.0)
    }

    @Test
    fun `choosing a place before the position arrives wins - the map stays on the place`() = scope.runTest {
        val selection = MapSelection()
        val viewModel = HomeViewModel(savedState, engine, location, selection, RouteDisplay())
        runCurrent()
        viewModel.onLocationAvailable(userAsked = false)

        val place = LatLng(14.0, 24.0)
        selection.select(SelectedPlace(name = "Main Station", label = "Example District", position = place))
        runCurrent()
        location.state.value = fix()
        runCurrent()

        assertEquals(place, map.cameraMoves.single().target)
    }

    // --- rotation and process death -----------------------------------------------------

    @Test
    fun `rotation keeps the camera - the same ViewModel does not open the map twice`() = scope.runTest {
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix()
        runCurrent()
        map.userMoves(CameraState(target = LatLng(15.0, 25.0), zoom = 12.0))
        runCurrent()

        // The screen is rebuilt for the new orientation; the ViewModel and its map state stay.
        viewModel.onLocationUnavailable(permissionLost = false)
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix(LatLng(10.5, 20.5))
        advanceTimeBy(60_000)

        assertEquals(1, map.cameraMoves.size)
        assertEquals(LatLng(15.0, 25.0), map.camera.value.target)
    }

    @Test
    fun `after process death the saved camera comes back and is not overridden`() = scope.runTest {
        // First life: the user ends up looking at some place.
        launchHome()
        map.userMoves(CameraState(target = LatLng(16.0, 26.0), zoom = 11.0, bearing = 15.0))
        runCurrent()

        // Second life: a new ViewModel, a new map, the same saved state from Android.
        val restoredEngine = FakeMapEngine()
        val restored = HomeViewModel(savedState, restoredEngine, location, MapSelection(), RouteDisplay())
        runCurrent()
        restored.onLocationAvailable(userAsked = false)
        location.state.value = fix()
        advanceTimeBy(60_000)

        val camera = CameraState(target = LatLng(16.0, 26.0), zoom = 11.0, bearing = 15.0)
        assertEquals(listOf<CameraState?>(camera), restoredEngine.initialCameras)
        assertTrue(restoredEngine.controller.cameraMoves.isEmpty())
    }

    // --- nothing stored or logged -------------------------------------------------------

    @Test
    fun `opening the map on a position stores only the camera and logs nothing`() = scope.runTest {
        ShadowLog.clear()
        val position = LatLng(12.345678, 98.765432)
        val viewModel = launchHome()
        viewModel.onLocationAvailable(userAsked = false)
        location.state.value = fix(position)
        runCurrent()

        // The camera is saved so that the map reopens where it was (system-held memory, as
        // before this change). No position, fix, accuracy or time is saved beside it.
        assertEquals(setOf("map_camera"), savedState.keys())
        assertEquals(4, savedState.get<DoubleArray>("map_camera")!!.size)

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        for (secret in listOf("12.345678", "98.765432", "12.34", "98.76")) {
            assertFalse("Logcat contains $secret", logged.contains(secret))
        }
        // Types that hold a position never print it.
        for (text in listOf(map.camera.value.toString(), location.state.value.toString(), position.toString())) {
            assertFalse(text, text.contains("12.3") || text.contains("98.7"))
        }
        Log.d("InitialCameraTest", "log capture works")
        assertTrue(ShadowLog.getLogs().any { it.msg == "log capture works" })
    }
}
