// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.location.FakeLocationEnvironment
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.GrantedLocation
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.directions.FakeRoutePreferences
import com.saferoute.app.feature.directions.FakeRouteRepository
import com.saferoute.app.feature.directions.FollowBannerTag
import com.saferoute.app.feature.directions.RouteSettings
import com.saferoute.app.feature.search.FAKE_STATION
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Following a route in the real [MainActivity], wired by Hilt as on a phone: Start, leaving the
 * screen and coming back, the permission taken away in system Settings, and ending. Routes,
 * positions and the map are fakes; the screens, the ViewModels and the lifecycle are real.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class FollowFlowTest {

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

    @Inject lateinit var environment: FakeLocationEnvironment

    @Inject lateinit var mapEngine: FakeMapEngine

    private val here = LatLng(10.0, 20.0)

    @Before
    fun inject() = hilt.inject()

    private fun string(id: Int): String = compose.activity.getString(id)

    /** Home leaves the screen (another app, the lock screen) and comes back. */
    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun screenKeptOn(view: View = compose.activity.window.decorView): Boolean =
        view.keepScreenOn || (view as? ViewGroup)?.children?.any { screenKeptOn(it) } == true

    private fun fixNow() {
        location.state.value = LocationState.Fix(fakeFix(position = here, timeMillis = System.currentTimeMillis()))
        compose.waitForIdle()
    }

    private fun routesToStation() {
        preferences.settings.value = RouteSettings(introSeen = true)
        environment.granted = GrantedLocation.Precise
        leaveAndReturn()
        fixNow()
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithTag("search-field").performTextInput("station")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(FAKE_STATION.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(FAKE_STATION.name).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.place_card_directions)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("26 min").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun start() {
        fixNow()
        compose.onNodeWithText(string(R.string.route_start)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `Start follows on screen only - leaving pauses with a note, and ending asks first`() {
        routesToStation()
        assertFalse(screenKeptOn())
        start()

        compose.onNodeWithTag(FollowBannerTag).assertIsDisplayed()
        assertTrue(screenKeptOn())
        // The camera is on the user, the alternative is gone, and the SOS control is in reach.
        val map = mapEngine.controller
        assertEquals(here, map.cameraMoves.last().target)
        assertEquals(
            listOf("route-fake-route-1", "route-travelled"),
            map.overlays.filterIsInstance<MapOverlay.Route>().map { it.id },
        )
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        // No permission was asked for and no new request was sent.
        assertEquals(null, shadowOf(compose.activity).lastRequestedPermission)
        assertEquals(1, routes.calls.size)

        // Off the screen the app reads no positions: location updates are stopped.
        val stops = location.stops
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(location.stops > stops)
        assertFalse(location.running)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_follow_paused)).assertIsDisplayed()
        assertTrue(screenKeptOn())

        // Back asks; Cancel keeps following; End returns to the routes.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_follow_end_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_follow_end_cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FollowBannerTag).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_follow_end)).performClick()
        compose.waitForIdle()
        // Two buttons say End now: the banner's, and the one in the question above it.
        compose.onAllNodesWithText(string(R.string.route_follow_end)).onLast().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(FollowBannerTag).assertCountEquals(0)
        assertFalse(screenKeptOn())
        assertEquals(2, map.overlays.count { it is MapOverlay.Route })
        compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed()
    }

    @Test
    fun `the permission taken away while following ends it and says what is needed`() {
        routesToStation()
        start()
        assertTrue(screenKeptOn())

        environment.granted = GrantedLocation.None
        leaveAndReturn()

        compose.onAllNodesWithTag(FollowBannerTag).assertCountEquals(0)
        assertFalse(screenKeptOn())
        compose.onNodeWithText(string(R.string.route_start_no_permission)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with approximate location Start explains and offers precise location instead of following`() {
        routesToStation()
        location.state.value =
            LocationState.Fix(fakeFix(position = here, timeMillis = System.currentTimeMillis(), isApproximate = true))
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_start)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(FollowBannerTag).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.route_start_needs_precise)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_start_use_precise)).performScrollTo().assertIsDisplayed()
        assertFalse(screenKeptOn())
    }
}
