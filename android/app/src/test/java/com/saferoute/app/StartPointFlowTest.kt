// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.directions.FAKE_ROUTE_OTHER
import com.saferoute.app.feature.directions.FakeRoutePreferences
import com.saferoute.app.feature.directions.FakeRouteRepository
import com.saferoute.app.feature.directions.RouteSettings
import com.saferoute.app.feature.directions.TravelMode
import com.saferoute.app.feature.search.FAKE_MARKET
import com.saferoute.app.feature.search.FAKE_STATION
import com.saferoute.app.testing.assertMinTouchTarget
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A start point of the user's choice and a route chosen on the map, in the real
 * [MainActivity]: directions → "Change start" → search → a place → routes from it, with
 * Preview instead of Start → back to the user's location. Search, routes and the map are fakes.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class StartPointFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject lateinit var routes: FakeRouteRepository

    @Inject lateinit var preferences: FakeRoutePreferences

    @Inject lateinit var location: FakeLocationRepository

    @Inject lateinit var mapEngine: FakeMapEngine

    private val here = LatLng(10.0, 20.0)

    @Before
    fun inject() = hilt.inject()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun searchAndChoose(name: String) {
        compose.onNodeWithTag("search-field").performTextInput("station")
        compose.onNodeWithTag("search-field").performImeAction()
        waitForText(name)
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
    }

    private fun routesToStation() {
        preferences.settings.value = RouteSettings(introSeen = true)
        location.state.value = LocationState.Fix(fakeFix(position = here, timeMillis = System.currentTimeMillis()))
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        searchAndChoose(FAKE_STATION.name)
        compose.onNodeWithText(string(R.string.place_card_directions)).performClick()
        waitForText("26 min")
    }

    private fun button(id: Int) = compose.onNodeWithText(string(id)).performScrollTo()

    @Test
    fun `Change start leads through search to routes from the chosen place, with Preview, and back`() {
        routesToStation()
        compose.onNodeWithText(string(R.string.route_from_my_location)).assertIsDisplayed()
        button(R.string.route_start).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_start_from_my_location)).assertCountEquals(0)

        compose.onNodeWithText(string(R.string.route_change_start), substring = true).assertMinTouchTarget().performClick()
        compose.waitForIdle()
        // The search field says what the choice is for.
        compose.onNodeWithText(string(R.string.route_start_search_hint)).assertIsDisplayed()
        searchAndChoose(FAKE_MARKET.name)

        // Back on Home: the same destination, routes from the market.
        waitForText(string(R.string.route_from_place, FAKE_MARKET.name))
        compose.onNodeWithText(string(R.string.route_title_to, FAKE_STATION.name)).assertIsDisplayed()
        assertEquals(
            FakeRouteRepository.Call(FAKE_MARKET.position, FAKE_STATION.position, TravelMode.Walking),
            routes.calls.last(),
        )
        assertEquals(2, routes.calls.size)
        // Preview with its reason in place of Start.
        compose.onAllNodesWithText(string(R.string.route_start)).assertCountEquals(0)
        // On this small test screen the sentence is in the part of the sheet that is pulled up.
        compose.onNodeWithText(string(R.string.route_preview_note)).assertExists()
        val fits = mapEngine.controller.fits.size
        button(R.string.route_preview).assertMinTouchTarget().performClick()
        compose.waitForIdle()
        assertEquals(fits + 1, mapEngine.controller.fits.size)
        // The SOS control is where it always is.
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()

        // Below the visible half of the sheet on this small test screen: clicked as TalkBack
        // would, by its action, not by a tap at a position.
        button(R.string.route_start_from_my_location).performSemanticsAction(SemanticsActions.OnClick)
        waitForText(string(R.string.route_from_my_location))
        assertEquals(here, routes.calls.last().origin)
        // Start is back (the sheet is still down from Preview, so it is not on screen here).
        compose.onNodeWithText(string(R.string.route_start)).assertExists()
        compose.onAllNodesWithText(string(R.string.route_preview)).assertCountEquals(0)
    }

    @Test
    fun `leaving search without choosing keeps the start, and the next search is an ordinary one`() {
        routesToStation()
        compose.onNodeWithText(string(R.string.route_change_start), substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_start_search_hint)).assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.route_from_my_location)).assertIsDisplayed()
        assertEquals(1, routes.calls.size)

        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(string(R.string.route_start_search_hint)).assertCountEquals(0)
        // A place chosen now is a new place on the map: directions to the station are over.
        searchAndChoose(FAKE_MARKET.name)
        compose.onNodeWithText(string(R.string.place_card_directions)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_title_to, FAKE_STATION.name)).assertCountEquals(0)
    }

    @Test
    fun `a tap on a route's line selects its card`() {
        routesToStation()
        compose.onNode(hasText("26 min")).assertIsSelected()

        mapEngine.controller.tapRoute("route-${FAKE_ROUTE_OTHER.id}")
        compose.waitForIdle()

        compose.onNode(hasText("29 min")).assertIsSelected()
        compose.onNode(hasText("26 min")).assertIsNotSelected()
        assertEquals(1, routes.calls.size)
    }
}
