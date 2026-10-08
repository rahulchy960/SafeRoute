// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.search.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * The states of [DirectionsViewModel]: the one-time note, loading, results, every reason for
 * having none, and above all that waiting for a sleeping routing service always ends.
 *
 * Time is virtual (`StandardTestDispatcher`): "ten seconds later" is `advanceTimeBy(10_000)`,
 * exact and instant. Robolectric is used only so that Android's log can be captured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DirectionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = FakeRouteRepository()
    private val preferences = FakeRoutePreferences(RouteSettings(introSeen = true))
    private val location = FakeLocationRepository()
    private val selection = MapSelection()
    private val display = RouteDisplay()
    private val clock = TestClock()
    private lateinit var viewModel: DirectionsViewModel

    /** Invented positions with digits that are easy to find in a log. */
    private val here = LatLng(10.123456, 20.654321)
    private val place = SelectedPlace("Main Station SECRETNAME", "Station Road", LatLng(10.987654, 20.123987))
    private val found = RouteOutcome.Found(listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER), FAKE_ROUTE_CREDIT)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        location.state.value = LocationState.Fix(fakeFix(position = here))
        selection.select(place)
        viewModel = DirectionsViewModel(repository, preferences, location, selection, display, clock)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val open get() = viewModel.state.value as DirectionsUiState.Open
    private val status get() = open.status

    private fun TestScope.openDirections() {
        viewModel.onDirectionsClick()
        runCurrent()
    }

    private fun failing(error: RouteError) {
        repository.answers = listOf(RouteOutcome.Failed(error))
    }

    @Test
    fun `starts closed and asks for nothing`() = scope.runTest {
        advanceUntilIdle()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `the first time ever shows the note, and nothing is sent until Continue`() = scope.runTest {
        preferences.settings.value = RouteSettings(introSeen = false)
        openDirections()
        assertEquals(DirectionsUiState.Intro(place), viewModel.state.value)
        assertTrue(repository.calls.isEmpty())

        viewModel.onIntroContinue()
        runCurrent()
        assertTrue(preferences.settings.value.introSeen)
        assertEquals(1, repository.calls.size)
        assertTrue(status is DirectionsStatus.Results)

        // Once is once: closing and opening again goes straight to the routes.
        viewModel.onClose()
        openDirections()
        assertTrue(viewModel.state.value is DirectionsUiState.Open)
    }

    @Test
    fun `Not now on the note sends nothing and remembers nothing`() = scope.runTest {
        preferences.settings.value = RouteSettings(introSeen = false)
        openDirections()
        viewModel.onClose()
        advanceUntilIdle()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertTrue(repository.calls.isEmpty())
        assertEquals(false, preferences.settings.value.introSeen)
    }

    @Test
    fun `asks from my location to the chosen place and shows the fastest route selected`() = scope.runTest {
        openDirections()
        assertEquals(listOf(FakeRouteRepository.Call(here, place.position, TravelMode.Walking)), repository.calls)
        val results = status as DirectionsStatus.Results
        assertEquals(listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER), results.routes)
        assertEquals(FAKE_ROUTE_FAST.id, results.selectedId)
        assertEquals(FAKE_ROUTE_CREDIT, results.attribution)
        // The map is told what to draw, and where the route starts.
        val shown = display.routes.value!!
        assertEquals(listOf(FAKE_ROUTE_FAST.id, FAKE_ROUTE_OTHER.id), shown.lines.map { it.id })
        assertEquals(FAKE_ROUTE_FAST.id, shown.selectedId)
        assertEquals(here, shown.start)
    }

    @Test
    fun `choosing an alternative selects it on the card and on the map without a new request or a new fit`() = scope.runTest {
        openDirections()
        val fit = display.routes.value!!.fitToken
        viewModel.onRouteSelect(FAKE_ROUTE_OTHER.id)
        assertEquals(FAKE_ROUTE_OTHER.id, (status as DirectionsStatus.Results).selectedId)
        assertEquals(FAKE_ROUTE_OTHER.id, display.routes.value!!.selectedId)
        assertEquals(fit, display.routes.value!!.fitToken)
        viewModel.onRouteSelect("no-such-route")
        assertEquals(FAKE_ROUTE_OTHER.id, (status as DirectionsStatus.Results).selectedId)
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `switching the mode asks again, remembers the choice, and the same mode twice does nothing`() = scope.runTest {
        openDirections()
        viewModel.onModeChange(TravelMode.Driving)
        runCurrent()
        assertEquals(TravelMode.Driving, open.mode)
        assertEquals(listOf(TravelMode.Walking, TravelMode.Driving), repository.calls.map { it.mode })
        assertEquals(TravelMode.Driving, preferences.settings.value.mode)

        viewModel.onModeChange(TravelMode.Driving)
        runCurrent()
        assertEquals(2, repository.calls.size)

        // The remembered mode is used the next time directions open.
        viewModel.onClose()
        openDirections()
        assertEquals(TravelMode.Driving, repository.calls.last().mode)
    }

    @Test
    fun `a new request cancels the one before, and only the newest answer is shown`() = scope.runTest {
        repository.gate = CompletableDeferred()
        openDirections()
        assertEquals(DirectionsStatus.Loading(), status)
        viewModel.onModeChange(TravelMode.Driving)
        runCurrent()
        assertEquals(2, repository.calls.size)

        repository.gate!!.complete(Unit)
        runCurrent()
        // The walking request was cancelled while it waited: one request ran to the end.
        assertEquals(1, repository.completed)
        assertTrue(status is DirectionsStatus.Results)
        assertEquals(TravelMode.Driving, open.mode)
    }

    @Test
    fun `double taps send one request - Directions twice, Try again while loading`() = scope.runTest {
        repository.gate = CompletableDeferred()
        viewModel.onDirectionsClick()
        viewModel.onDirectionsClick()
        runCurrent()
        viewModel.onRetry()
        viewModel.onRetry()
        runCurrent()
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `each answer about the places is shown as it is, without asking again`() = scope.runTest {
        for (error in listOf(RouteError.OutsideCovered, RouteError.NoRoute, RouteError.NotRoutable, RouteError.TooLong)) {
            failing(error)
            openDirections()
            advanceUntilIdle()
            assertEquals(DirectionsStatus.Failed(error), status)
            assertNull(display.routes.value)
            viewModel.onClose()
        }
        assertEquals(4, repository.calls.size)
    }

    @Test
    fun `offline, rate limited and unavailable stop at once with their own state, and Try again asks again`() = scope.runTest {
        for (error in listOf(RouteError.Offline, RouteError.RateLimited(30), RouteError.Unavailable)) {
            failing(error)
            openDirections()
            advanceUntilIdle()
            assertEquals(DirectionsStatus.Failed(error), status)
            viewModel.onClose()
        }
        failing(RouteError.Offline)
        openDirections()
        repository.answers = listOf(found)
        viewModel.onRetry()
        runCurrent()
        assertTrue(status is DirectionsStatus.Results)
    }

    @Test
    fun `a sleeping routing service - the app says so, waits Retry-After, asks again and shows the routes`() = scope.runTest {
        repository.answers = listOf(RouteOutcome.Failed(RouteError.Starting(retryAfterSeconds = 10)), found)
        openDirections()
        assertEquals(DirectionsStatus.Loading(starting = true), status)
        assertEquals(1, repository.calls.size)

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, repository.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, repository.calls.size)
        assertTrue(status is DirectionsStatus.Results)
    }

    @Test
    fun `waiting for the routing service is bounded - two more tries, then Try again, never an endless spinner`() = scope.runTest {
        failing(RouteError.Starting(retryAfterSeconds = 10))
        openDirections()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(DirectionsStatus.Loading(starting = true), status)
        advanceTimeBy(10_000)
        runCurrent()
        // Three requests in all (the first and two automatic ones), then it stops by itself.
        assertEquals(3, repository.calls.size)
        assertEquals(DirectionsStatus.Failed(RouteError.Starting(10), stillStarting = true), status)

        // Nothing more happens without the user: no fourth request an hour later.
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(3, repository.calls.size)

        // Try again starts a fresh, equally bounded round.
        repository.answers = listOf(found)
        viewModel.onRetry()
        runCurrent()
        assertEquals(4, repository.calls.size)
        assertTrue(status is DirectionsStatus.Results)
    }

    @Test
    fun `an absurd Retry-After is capped, and a slow request is explained after five seconds`() = scope.runTest {
        repository.answers = listOf(RouteOutcome.Failed(RouteError.Starting(retryAfterSeconds = 86_400)), found)
        openDirections()
        advanceTimeBy(DirectionsViewModel.MAX_WAIT_SECONDS * 1000L)
        runCurrent()
        assertTrue(status is DirectionsStatus.Results)

        viewModel.onClose()
        repository.gate = CompletableDeferred()
        openDirections()
        assertEquals(DirectionsStatus.Loading(starting = false), status)
        advanceTimeBy(DirectionsViewModel.SLOW_HINT_MILLIS)
        runCurrent()
        assertEquals(DirectionsStatus.Loading(starting = true), status)
        repository.gate!!.complete(Unit)
        runCurrent()
        assertTrue(status is DirectionsStatus.Results)
    }

    @Test
    fun `closing while waiting cancels the wait - no request is sent afterwards`() = scope.runTest {
        failing(RouteError.Starting(retryAfterSeconds = 10))
        openDirections()
        viewModel.onClose()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, repository.calls.size)
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertNull(display.routes.value)
    }

    @Test
    fun `without a position it says why, sends nothing, and asks for routes the moment one arrives`() = scope.runTest {
        for ((state, problem) in listOf(
            LocationState.NoPermission to OriginProblem.NoPermission,
            LocationState.Searching to OriginProblem.Searching,
            LocationState.Unavailable to OriginProblem.Unavailable,
        )) {
            location.state.value = state
            openDirections()
            assertEquals(DirectionsStatus.NeedsOrigin(problem), status)
            viewModel.onClose()
        }
        assertTrue(repository.calls.isEmpty())

        location.state.value = LocationState.NoPermission
        openDirections()
        location.state.value = LocationState.Searching
        runCurrent()
        assertEquals(DirectionsStatus.NeedsOrigin(OriginProblem.Searching), status)
        location.state.value = LocationState.Fix(fakeFix(position = here))
        runCurrent()
        assertEquals(listOf(here), repository.calls.map { it.origin })
        assertTrue(status is DirectionsStatus.Results)
    }

    @Test
    fun `a later position does not ask again - there is no automatic rerouting`() = scope.runTest {
        openDirections()
        location.state.value = LocationState.Fix(fakeFix(position = LatLng(10.2, 20.7)))
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `an old position is used as the start`() = scope.runTest {
        location.state.value = LocationState.Stale(fakeFix(position = here), ageSeconds = 90)
        openDirections()
        assertEquals(here, repository.calls.single().origin)
    }

    @Test
    fun `another place, or none, ends directions and takes the routes off the map`() = scope.runTest {
        openDirections()
        selection.clear()
        runCurrent()
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
        assertNull(display.routes.value)

        viewModel.onDirectionsClick()
        runCurrent()
        // No destination: Directions does nothing.
        assertEquals(DirectionsUiState.Closed, viewModel.state.value)
    }

    @Test
    fun `nothing about a route reaches the log or a toString`() = scope.runTest {
        // This sees Android's log (Logcat) and what toString() would print. It cannot see the
        // network: that requests go only to the SafeRoute API is ApiRouteRepositoryTest's job.
        ShadowLog.clear()
        openDirections()
        viewModel.onRouteSelect(FAKE_ROUTE_OTHER.id)
        viewModel.onModeChange(TravelMode.Driving)
        runCurrent()
        failing(RouteError.NotRoutable)
        viewModel.onRetry()
        advanceUntilIdle()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val printed = listOf(
            viewModel.state.value,
            DirectionsUiState.Open(place, TravelMode.Walking, DirectionsStatus.Results(found.routes, "x", "y")),
            DirectionsUiState.Intro(place),
            found,
            FAKE_ROUTE_FAST,
            display.routes.value,
            repository.calls.first().origin,
        ).joinToString(" ")
        listOf("10.12", "20.65", "10.98", "20.12", "10.25", "20.25", "SECRETNAME", "Station Road", "fake-route-1")
            .forEach { secret ->
                assertTrue("log contains $secret", secret !in logged)
                assertTrue("toString contains $secret", secret !in printed)
            }
    }
}
