// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationFix
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.search.TestClock
import kotlin.math.cos
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Following a route in [DirectionsViewModel] (ADR 0022): when it may start, what each kind of
 * position does to it, and every way it ends. Positions come from a fake, time is virtual, and
 * the clock is moved by hand together with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FollowRouteTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = FakeRouteRepository()
    private val location = FakeLocationRepository()
    private val selection = MapSelection()
    private val display = RouteDisplay()
    private val clock = TestClock()
    private lateinit var viewModel: DirectionsViewModel

    /** A point [east] and [north] metres from an invented origin with digits easy to find. */
    private fun at(east: Double, north: Double = 0.0) = LatLng(
        latitude = 10.123456 + north / 111_320.0,
        longitude = 20.654321 + east / (111_320.0 * cos(Math.toRadians(10.123456))),
    )

    /** One kilometre due east, ten minutes. */
    private val route = RouteOption("walk", 1000, 600, listOf(at(0.0), at(1000.0)))
    private val other = RouteOption("other", 1300, 800, listOf(at(0.0), at(0.0, 150.0), at(1000.0)))
    private val place = SelectedPlace("Main Station SECRETNAME", "Station Road", at(1000.0))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.answers = listOf(RouteOutcome.Found(listOf(route, other), FAKE_ROUTE_CREDIT))
        selection.select(place)
        viewModel = DirectionsViewModel(
            repository,
            FakeRoutePreferences(RouteSettings(introSeen = true)),
            location,
            selection,
            display,
            clock,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Following ticks once a second for as long as it lasts, and a test waits for all pending
     * work at its end: so every test ends by closing directions, which stops the ticking.
     */
    private fun followTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        // Also when an assertion fails: otherwise the failure would show as a hang.
        try {
            body()
        } finally {
            viewModel.onClose()
        }
    }

    private val open get() = viewModel.state.value as DirectionsUiState.Open
    private val follow get() = open.follow

    /** The phone reports a position now. */
    private fun TestScope.fix(
        east: Number,
        north: Number = 0,
        accuracy: Float = 10f,
        approximate: Boolean = false,
        heading: Float? = null,
    ) {
        location.state.value = LocationState.Fix(
            LocationFix(at(east.toDouble(), north.toDouble()), accuracy, clock.nowMillis, approximate, heading),
        )
        runCurrent()
    }

    private fun TestScope.wait(seconds: Int) = repeat(seconds) {
        clock.nowMillis += 1000
        advanceTimeBy(1000)
        runCurrent()
    }

    /** A position each second for [seconds] seconds. */
    private fun TestScope.stay(seconds: Int, east: Number, north: Number = 0) = repeat(seconds) {
        wait(1)
        fix(east, north)
    }

    private fun TestScope.openRoutes() {
        fix(0)
        viewModel.onDirectionsClick()
        runCurrent()
        assertTrue(open.status is DirectionsStatus.Results)
    }

    private fun TestScope.start() {
        openRoutes()
        viewModel.onStartClick()
        runCurrent()
        assertNotNull(follow)
    }

    private fun TestScope.goOffRoute() {
        start()
        stay(12, east = 100, north = 90)
        assertTrue(follow!!.offRoute)
    }

    @Test
    fun `Start begins following from a precise position of the last seconds`() = followTest {
        openRoutes()
        wait(10)
        viewModel.onStartClick()
        runCurrent()

        assertEquals(FollowState(1000, 600, clock.nowMillis + 600_000), follow)
        assertNull(open.startProblem)
        assertEquals(at(0.0), display.following.value!!.position)
        // A second tap, another mode or another route change nothing while it runs.
        viewModel.onStartClick()
        viewModel.onModeChange(TravelMode.Driving)
        viewModel.onRouteSelect(other.id)
        viewModel.onRetry()
        runCurrent()
        assertEquals(TravelMode.Walking, open.mode)
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `Start never begins without the permission, with approximate location or without a recent position`() = followTest {
        openRoutes()
        wait(11)
        viewModel.onStartClick()
        assertEquals(StartProblem.NoRecentFix, open.startProblem)

        location.state.value = LocationState.Stale(LocationFix(at(0.0), 10f, clock.nowMillis, false), 40)
        viewModel.onStartClick()
        assertEquals(StartProblem.NoRecentFix, open.startProblem)

        fix(0, approximate = true)
        viewModel.onStartClick()
        assertEquals(StartProblem.NeedsPrecise, open.startProblem)

        location.state.value = LocationState.NoPermission
        viewModel.onStartClick()
        assertEquals(StartProblem.NoPermission, open.startProblem)

        assertNull(follow)
        assertNull(display.following.value)
        // The sentence goes when it stops being true.
        fix(0)
        assertNull(open.startProblem)
    }

    @Test
    fun `positions move the numbers, the travelled part and the direction the map faces`() = followTest {
        start()
        wait(1)
        fix(40, heading = 90f)
        assertEquals(960, follow!!.remainingMeters)
        assertEquals(576, follow!!.remainingSeconds)
        assertEquals(clock.nowMillis + 576_000, follow!!.arrivalMillis)
        val view = display.following.value!!
        assertEquals(90.0, view.bearing, 0.0)
        assertEquals(2, view.travelled.size)
        // Standing still: north up.
        wait(1)
        fix(41)
        assertEquals(0.0, display.following.value!!.bearing, 0.0)
    }

    @Test
    fun `ten seconds without a position says Searching for GPS and keeps the route`() = followTest {
        start()
        stay(3, east = 30)
        wait(10)
        assertFalse(follow!!.gpsLost)
        wait(1)
        assertTrue(follow!!.gpsLost)
        assertEquals(970, follow!!.remainingMeters)
        assertNotNull(display.routes.value)
        assertNotNull(display.following.value)

        fix(60)
        assertFalse(follow!!.gpsLost)
    }

    @Test
    fun `off the route shows after ten seconds, asks for nothing by itself, and goes when the user is back`() = followTest {
        start()
        stay(10, east = 100, north = 90)
        assertFalse(follow!!.offRoute)
        stay(50, east = 100, north = 90)
        assertTrue(follow!!.offRoute)
        // No automatic rerouting, however long it lasts.
        assertEquals(1, repository.calls.size)

        wait(1)
        fix(110)
        assertFalse(follow!!.offRoute)
    }

    @Test
    fun `arriving stops following for good`() = followTest {
        start()
        wait(1)
        fix(980)
        assertTrue(follow!!.arrived)
        assertFalse(follow!!.active)
        assertNull(display.following.value)
        // Nothing runs any more: no "Searching for GPS", no reaction to positions.
        wait(30)
        fix(500)
        assertEquals(FollowState(0, 0, follow!!.arrivalMillis, arrived = true), follow)
        viewModel.onEndClick()
        assertFalse(follow!!.confirmEnd)

        viewModel.onClose()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
    }

    @Test
    fun `losing the permission or precise location while following ends it with the reason`() = followTest {
        start()
        viewModel.onLocationPermissionLost()
        assertNull(follow)
        assertEquals(StartProblem.NoPermission, open.startProblem)
        assertNull(display.following.value)
        assertNotNull(display.routes.value)

        fix(0)
        viewModel.onStartClick()
        runCurrent()
        location.state.value = LocationState.NoPermission
        runCurrent()
        assertNull(follow)
        assertEquals(StartProblem.NoPermission, open.startProblem)

        fix(0)
        viewModel.onStartClick()
        runCurrent()
        wait(1)
        fix(20, approximate = true)
        assertNull(follow)
        assertEquals(StartProblem.NeedsPrecise, open.startProblem)
        // Nothing keeps counting afterwards.
        wait(30)
        assertNull(follow)
    }

    @Test
    fun `in the background nothing is counted, and coming back says so once`() = followTest {
        start()
        stay(11, east = 100, north = 90)
        viewModel.onBackground()
        wait(120)
        assertFalse(follow!!.gpsLost)

        viewModel.onForeground()
        assertTrue(follow!!.pausedNote)
        // The time away is neither "no GPS" nor "off the route".
        assertFalse(follow!!.gpsLost)
        assertFalse(follow!!.offRoute)
        wait(1)
        fix(100, 90)
        assertFalse(follow!!.offRoute)
        assertTrue(follow!!.pausedNote)
        wait(11)
        assertTrue(follow!!.gpsLost)

        viewModel.onPausedNoteDismiss()
        assertFalse(follow!!.pausedNote)
        viewModel.onBackground()
        viewModel.onForeground()
        assertFalse(follow!!.pausedNote)
        // Not following: leaving and coming back does nothing at all.
        viewModel.onEndConfirm()
        viewModel.onBackground()
        viewModel.onForeground()
        assertNull(follow)
    }

    @Test
    fun `End asks first - Cancel keeps following, End returns to the routes`() = followTest {
        start()
        viewModel.onEndClick()
        assertTrue(follow!!.confirmEnd)
        viewModel.onEndCancel()
        assertFalse(follow!!.confirmEnd)
        assertNotNull(display.following.value)

        viewModel.onEndClick()
        viewModel.onEndConfirm()
        assertNull(follow)
        assertNull(open.startProblem)
        assertNull(display.following.value)
        assertTrue(open.status is DirectionsStatus.Results)
        assertNotNull(display.routes.value)
        wait(30)
        assertNull(follow)
    }

    @Test
    fun `closing directions or choosing another place ends following`() = followTest {
        start()
        viewModel.onClose()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertNull(display.following.value)

        viewModel.onDirectionsClick()
        runCurrent()
        fix(0)
        viewModel.onStartClick()
        runCurrent()
        assertNotNull(follow)
        selection.select(SelectedPlace("Elsewhere", "", at(0.0, 500.0)))
        runCurrent()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertNull(display.following.value)
    }

    @Test
    fun `Recalculate asks once from the current position and keeps following on the new route`() = followTest {
        goOffRoute()
        val detour = RouteOption("detour", 950, 570, listOf(at(100.0, 90.0), at(1000.0)))
        repository.answers = listOf(RouteOutcome.Found(listOf(detour), FAKE_ROUTE_CREDIT))
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }

        viewModel.onRecalculate()
        viewModel.onRecalculate()
        runCurrent()
        viewModel.onRecalculate()
        runCurrent()
        assertEquals(Recalculation.Running, follow!!.recalculation)
        // Three taps, one request; the old route is still the one on the map.
        assertEquals(2, repository.calls.size)
        assertEquals(FakeRouteRepository.Call(at(100.0, 90.0), place.position, TravelMode.Walking), repository.calls.last())
        assertEquals(route.id, display.routes.value!!.selectedId)

        gate.complete(Unit)
        runCurrent()
        assertEquals(FollowState(950, 570, clock.nowMillis + 570_000), follow)
        assertEquals(DirectionsStatus.Results(listOf(detour), detour.id, FAKE_ROUTE_CREDIT), open.status)
        assertEquals(detour.id, display.routes.value!!.selectedId)
        assertNotNull(display.following.value)
        // And it is the new line that is followed.
        wait(1)
        fix(150, 85)
        assertEquals(897.0, follow!!.remainingMeters.toDouble(), 3.0)
    }

    @Test
    fun `a Recalculate that fails says why, keeps the route and does not ask again by itself`() = followTest {
        goOffRoute()
        val errors = listOf(RouteError.RateLimited(30), RouteError.Starting(5), RouteError.Offline)
        errors.forEachIndexed { index, error ->
            repository.answers = listOf(RouteOutcome.Failed(error))
            viewModel.onRecalculate()
            runCurrent()
            assertEquals(Recalculation.Failed(error), follow!!.recalculation)
            assertEquals(2 + index, repository.calls.size)
            stay(20, east = 100, north = 90)
            assertEquals(2 + index, repository.calls.size)
            assertEquals(Recalculation.Failed(error), follow!!.recalculation)
            assertEquals(route.id, display.routes.value!!.selectedId)
        }
        // Back on the route: the banner and its failure are gone.
        wait(1)
        fix(110)
        assertEquals(Recalculation.Idle, follow!!.recalculation)
    }

    @Test
    fun `a Recalculate that gets no answer gives up after twenty seconds`() = followTest {
        goOffRoute()
        repository.gate = CompletableDeferred()
        viewModel.onRecalculate()
        runCurrent()
        wait(19)
        assertEquals(Recalculation.Running, follow!!.recalculation)
        wait(2)
        assertEquals(Recalculation.Failed(RouteError.Unavailable), follow!!.recalculation)
        assertEquals(0, repository.completed - 1)
    }

    @Test
    fun `following logs nothing and its state prints no position`() = followTest {
        ShadowLog.clear()
        goOffRoute()
        viewModel.onRecalculate()
        runCurrent()
        wait(1)
        fix(980)

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val printed = listOf(viewModel.state.value, display.following.value, display.routes.value, route)
            .joinToString(" ") + RouteTracker(route, 0).progress + FollowState(1, 2, 3)
        listOf("10.12", "20.65", "20.66", "SECRETNAME", "Station Road").forEach { secret ->
            assertTrue("log contains $secret", secret !in logged)
            assertTrue("toString contains $secret", secret !in printed)
        }
    }
}
