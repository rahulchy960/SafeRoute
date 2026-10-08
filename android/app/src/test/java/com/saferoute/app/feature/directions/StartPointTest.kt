// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import androidx.lifecycle.SavedStateHandle
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapSelection
import com.saferoute.app.core.map.RouteDisplay
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.home.HomeViewModel
import com.saferoute.app.feature.search.FAKE_MARKET
import com.saferoute.app.feature.search.FakeSearchRepository
import com.saferoute.app.feature.search.SearchAreaParts
import com.saferoute.app.feature.search.SearchViewModel
import com.saferoute.app.feature.search.TestClock
import com.saferoute.app.core.session.AppLocale
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * A start point of the user's choice, and a route chosen by tapping its line (P012c2b): what
 * [DirectionsViewModel] does with each, with the real [MapSelection] and [RouteDisplay]
 * between it, search and the map screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StartPointTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = FakeRouteRepository()
    private val location = FakeLocationRepository()
    private val selection = MapSelection()
    private val display = RouteDisplay()
    private val clock = TestClock()
    private lateinit var viewModel: DirectionsViewModel

    /** Invented positions with digits that are easy to find in a log. */
    private val here = LatLng(10.123456, 20.654321)
    private val place = SelectedPlace("Main Station", "Station Road", LatLng(10.987654, 20.123987))
    private val start = SelectedPlace("Old Bridge SECRETSTART", "River Road", LatLng(10.555444, 20.333222))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        location.state.value = LocationState.Fix(fakeFix(position = here, timeMillis = clock.nowMillis))
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

    private val open get() = viewModel.state.value as DirectionsUiState.Open
    private val results get() = open.status as DirectionsStatus.Results

    private fun TestScope.openRoutes() {
        viewModel.onDirectionsClick()
        runCurrent()
        assertTrue(open.status is DirectionsStatus.Results)
    }

    /** "Change", then a place picked in search, as the search screen hands it over. */
    private fun TestScope.chooseStart(chosen: SelectedPlace = start) {
        assertTrue(viewModel.onChangeStartClick())
        selection.choose(chosen)
        runCurrent()
    }

    @Test
    fun `a chosen place becomes the start - routes are asked for again from it`() = scope.runTest {
        openRoutes()
        assertNull(open.origin)
        chooseStart()

        assertEquals(start, open.origin)
        assertEquals(
            listOf(
                FakeRouteRepository.Call(here, place.position, TravelMode.Walking),
                FakeRouteRepository.Call(start.position, place.position, TravelMode.Walking),
            ),
            repository.calls,
        )
        assertEquals(start.position, display.routes.value!!.start)
        // The place on the map is still the destination, and directions are still open.
        assertEquals(place, selection.selected.value)
        assertFalse(selection.choosingStart)
        // Another mode keeps the chosen start.
        viewModel.onModeChange(TravelMode.Driving)
        runCurrent()
        assertEquals(FakeRouteRepository.Call(start.position, place.position, TravelMode.Driving), repository.calls.last())
    }

    @Test
    fun `Use my location switches back and asks again from the user's position`() = scope.runTest {
        openRoutes()
        chooseStart()
        viewModel.onUseMyLocationAsStart()
        runCurrent()

        assertNull(open.origin)
        assertEquals(here, repository.calls.last().origin)
        assertEquals(3, repository.calls.size)
        // Already from the user's location: a second tap asks for nothing.
        viewModel.onUseMyLocationAsStart()
        runCurrent()
        assertEquals(3, repository.calls.size)
    }

    @Test
    fun `a chosen start needs no location at all`() = scope.runTest {
        location.state.value = LocationState.NoPermission
        viewModel.onDirectionsClick()
        runCurrent()
        assertEquals(DirectionsStatus.NeedsOrigin(OriginProblem.NoPermission), open.status)

        chooseStart()
        assertTrue(open.status is DirectionsStatus.Results)
        assertEquals(start.position, repository.calls.single().origin)
        // A position arriving later does not replace the start the user chose.
        location.state.value = LocationState.Fix(fakeFix(position = here, timeMillis = clock.nowMillis))
        runCurrent()
        assertEquals(1, repository.calls.size)
        assertEquals(start, open.origin)
    }

    @Test
    fun `Start follows only from the user's location - from a chosen start there is Preview`() = scope.runTest {
        openRoutes()
        chooseStart()
        val fitted = display.routes.value!!.fitToken

        viewModel.onStartClick()
        runCurrent()
        assertNull(open.follow)
        assertNull(open.startProblem)
        assertNull(display.following.value)

        viewModel.onPreviewClick()
        assertTrue(display.routes.value!!.fitToken != fitted)

        viewModel.onUseMyLocationAsStart()
        runCurrent()
        viewModel.onStartClick()
        runCurrent()
        assertTrue(open.follow != null)
        // While a route is followed its start cannot be changed.
        assertFalse(viewModel.onChangeStartClick())
        assertFalse(selection.choosingStart)
        viewModel.onClose()
    }

    @Test
    fun `Change is refused without routes, and leaving search without a choice changes nothing`() = scope.runTest {
        assertFalse(viewModel.onChangeStartClick())
        assertFalse(selection.choosingStart)

        openRoutes()
        assertTrue(viewModel.onChangeStartClick())
        // The search screen as the app builds it: it opens in "choose a start" mode ...
        val parts = SearchAreaParts()
        val search = SearchViewModel(FakeSearchRepository(), AppLocale { "en" }, selection, parts.provider)
        assertTrue(search.choosingStart)
        // ... and a result tapped there is the start, not a new place on the map.
        search.onPlaceChosen(FAKE_MARKET)
        runCurrent()
        assertEquals(FAKE_MARKET.position, open.origin!!.position)
        assertEquals(place, selection.selected.value)

        // The next search is an ordinary one.
        assertFalse(SearchViewModel(FakeSearchRepository(), AppLocale { "en" }, selection, parts.provider).choosingStart)
    }

    @Test
    fun `a tap on a route's line on the map selects it in the list, as a tap on its card does`() = scope.runTest {
        val engine = FakeMapEngine()
        val home = HomeViewModel(SavedStateHandle(), engine, location, selection, display)
        runCurrent()
        openRoutes()
        assertEquals(FAKE_ROUTE_FAST.id, results.selectedId)

        engine.controller.tapRoute("route-${FAKE_ROUTE_OTHER.id}")
        runCurrent()
        assertEquals(FAKE_ROUTE_OTHER.id, results.selectedId)
        assertEquals(FAKE_ROUTE_OTHER.id, display.routes.value!!.selectedId)
        // The selected route is drawn strong and last; choosing does not move the map.
        val drawn = engine.controller.overlays.filterIsInstance<com.saferoute.app.core.map.MapOverlay.Route>()
        assertEquals("route-${FAKE_ROUTE_OTHER.id}", drawn.last().id)
        assertTrue(drawn.last().selected)
        assertEquals(1, engine.controller.fits.size)
        assertEquals(1, repository.calls.size)

        // A tap that is not a route of this list, and any tap while a route is followed, do nothing.
        engine.controller.tapRoute("route-unknown")
        engine.controller.tapRoute("selected-place")
        runCurrent()
        assertEquals(FAKE_ROUTE_OTHER.id, results.selectedId)
        viewModel.onStartClick()
        runCurrent()
        engine.controller.tapRoute("route-${FAKE_ROUTE_FAST.id}")
        runCurrent()
        assertEquals(FAKE_ROUTE_OTHER.id, results.selectedId)
        viewModel.onClose()
        assertTrue(home.recentreOffered.value.not())
    }

    @Test
    fun `the chosen start is not logged and does not print`() = scope.runTest {
        ShadowLog.clear()
        openRoutes()
        chooseStart()
        viewModel.onPreviewClick()
        viewModel.onUseMyLocationAsStart()
        runCurrent()
        chooseStart()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        val printed = listOf(viewModel.state.value, display.routes.value, selection.selected.value, open.origin)
            .joinToString(" ")
        listOf("10.55", "20.33", "SECRETSTART", "River Road").forEach { secret ->
            assertTrue("log contains $secret", secret !in logged)
            assertTrue("toString contains $secret", secret !in printed)
        }
    }
}
