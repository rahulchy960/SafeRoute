// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.location.FakeLocationRepository
import com.saferoute.app.core.location.LocationState
import com.saferoute.app.core.location.fakeFix
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.boundsOf
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.directions.FAKE_ROUTE_CREDIT
import com.saferoute.app.feature.directions.FAKE_ROUTE_FAST
import com.saferoute.app.feature.directions.FakeRoutePreferences
import com.saferoute.app.feature.directions.FakeRouteRepository
import com.saferoute.app.feature.directions.RouteSettings
import com.saferoute.app.feature.directions.TravelMode
import com.saferoute.app.feature.search.FAKE_STATION
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Directions from end to end in the real [MainActivity]: a place from search → Directions →
 * the one-time note → routes in the sheet and on the map → back. The routes come from the fake
 * repository (no server) and the map is the fake engine (no native code); everything between
 * them is the real app, wired by Hilt as on a phone.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class DirectionsFlowTest {

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

    /** An invented position with digits that are easy to find in a log. */
    private val here = LatLng(10.123456, 20.654321)

    @Before
    fun inject() = hilt.inject()

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Search, choose the fake station, and tap Directions on its card. */
    private fun directionsToStation() {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithTag("search-field").performTextInput("station")
        compose.onNodeWithTag("search-field").performImeAction()
        waitForText(FAKE_STATION.name)
        compose.onNodeWithText(FAKE_STATION.name).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.place_card_directions)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the first time - the note, then routes from my location, drawn on the map, and back in two steps`() {
        location.state.value = LocationState.Fix(fakeFix(position = here))
        directionsToStation()

        // Nothing has left the phone yet, and nothing asked for a permission.
        compose.onNodeWithText(string(R.string.route_intro_title)).assertIsDisplayed()
        assertTrue(routes.calls.isEmpty())
        assertEquals(null, shadowOf(compose.activity).lastRequestedPermission)

        compose.onNodeWithText(string(R.string.route_intro_continue)).performClick()
        waitForText("26 min")
        assertEquals(listOf(FakeRouteRepository.Call(here, FAKE_STATION.position, TravelMode.Walking)), routes.calls)
        assertTrue(preferences.settings.value.introSeen)
        compose.onNodeWithText(string(R.string.route_fastest)).assertIsDisplayed()

        // On the map: both routes, the start ring, the destination pin; the camera fits the
        // selected route.
        val map = mapEngine.controller
        assertEquals(2, map.overlays.count { it is MapOverlay.Route })
        assertTrue(map.overlays.any { it.id == "route-start" })
        assertEquals("selected-place", map.overlays.last().id)
        assertEquals(listOf(boundsOf(FAKE_ROUTE_FAST.points)), map.fits)

        // The emergency button is where it always is.
        compose.onNodeWithText(string(R.string.emergency_button_label)).assertIsDisplayed()

        // Back closes directions and leaves the place; the next back clears the place.
        pressSystemBack()
        compose.onNodeWithText(string(R.string.place_card_directions)).assertIsDisplayed()
        assertTrue(map.overlays.none { it is MapOverlay.Route })
        pressSystemBack()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `without the location permission it explains, sends nothing, and never asks by itself`() {
        preferences.settings.value = RouteSettings(introSeen = true)
        directionsToStation()

        compose.onNodeWithText(string(R.string.route_origin_no_permission)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_use_my_location)).assertIsDisplayed()
        assertTrue(routes.calls.isEmpty())
        // Opening directions is not a tap on "my location": no system dialog was requested.
        assertEquals(null, shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    fun `while routes load the emergency button still reaches the dialer`() {
        preferences.settings.value = RouteSettings(introSeen = true)
        location.state.value = LocationState.Fix(fakeFix(position = here))
        routes.gate = CompletableDeferred()
        directionsToStation()
        compose.onNodeWithText(string(R.string.route_loading)).assertIsDisplayed()

        compose.onNodeWithText(string(R.string.emergency_button_label)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.waitForIdle()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertEquals("tel:112", intent?.dataString)
        routes.gate!!.complete(Unit)
    }

    @Test
    fun `the start, the destination, the routes and the credit never reach the log`() {
        // Android's log only. The request itself goes to the fake repository here; that the
        // real one talks to the SafeRoute API alone is ApiRouteRepositoryTest's job.
        ShadowLog.clear()
        preferences.settings.value = RouteSettings(introSeen = true)
        location.state.value = LocationState.Fix(fakeFix(position = here))
        directionsToStation()
        waitForText("26 min")
        compose.onNodeWithText(string(R.string.route_mode_driving)).performClick()
        compose.waitForIdle()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        listOf("10.12", "20.65", "10.25", "20.25", "10.5", "20.5", FAKE_STATION.name, FAKE_ROUTE_FAST.id, FAKE_ROUTE_CREDIT)
            .forEach { secret -> assertFalse("log contains $secret", secret in logged) }
    }
}
