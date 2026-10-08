// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app

import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.CameraState
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.core.map.FakeMapEngine
import com.saferoute.app.core.map.MapOverlay
import com.saferoute.app.core.map.MarkerStyle
import com.saferoute.app.core.session.FakeSession
import com.saferoute.app.core.session.Session
import com.saferoute.app.core.session.SessionState
import com.saferoute.app.core.session.di.SessionBindingModule
import com.saferoute.app.feature.search.FAKE_STATION
import com.saferoute.app.feature.search.FakeSearchRepository
import com.saferoute.app.feature.search.SearchError
import com.saferoute.app.feature.search.SearchOutcome
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Search from end to end in the real [MainActivity]: Home → search → a result → Home with the
 * pin and the place card → back. The search itself is the fake repository (no server); the map
 * is the fake engine (no native code). Everything between them is the real app.
 */
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w360dp-h640dp")
class SearchFlowTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val session: Session = FakeSession(SessionState.Ready)

    @Inject
    lateinit var search: FakeSearchRepository

    @Inject
    lateinit var mapEngine: FakeMapEngine

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

    /** Opens search, types and presses the keyboard's Search key (which skips the typing wait). */
    private fun searchFor(text: String) {
        compose.onNodeWithText(string(R.string.search_hint)).performClick()
        compose.onNodeWithTag("search-field").performTextInput(text)
        compose.onNodeWithTag("search-field").performImeAction()
    }

    @Test
    fun `search, choose a result, see the pin and the card, then back clears it and back again leaves`() {
        searchFor("station")
        waitForText(FAKE_STATION.name)
        compose.onNodeWithText(FAKE_STATION.name).performClick()
        compose.waitForIdle()

        // Home again, with the place: card in the sheet, pin on the map, camera on the place.
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).assertIsDisplayed()
        compose.onNodeWithText(FAKE_STATION.name).assertIsDisplayed()
        compose.onNodeWithText(FAKE_STATION.label).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertDoesNotExist()
        val map = mapEngine.controller
        assertEquals(
            listOf<MapOverlay>(MapOverlay.Marker("selected-place", FAKE_STATION.position, style = MarkerStyle.Place)),
            map.overlays,
        )
        assertEquals(FAKE_STATION.position, map.camera.value.target)

        // Back takes the place away first and stays on Home.
        pressSystemBack()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
        compose.onNodeWithText(FAKE_STATION.name).assertDoesNotExist()
        assertTrue(map.overlays.isEmpty())
        assertFalse(compose.activity.isFinishing)

        // The next back is the normal one.
        pressSystemBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `the search prefers the area the map shows, and needs no location permission`() {
        // Zoomed in on a town: that is an area. (The whole-region overview is not; see
        // HomePlaceTest.)
        val town = CameraState(target = LatLng(12.0, 22.0), zoom = 13.0)
        compose.runOnUiThread { mapEngine.controller.userMoves(town) }
        searchFor("station")
        waitForText(FAKE_STATION.name)

        val call = search.calls.single()
        assertEquals("station", call.query)
        assertEquals(town.target, call.near)
        assertEquals("en", call.language)
        // No permission dialog was ever requested for a search.
        assertEquals(null, shadowOf(compose.activity).lastRequestedPermission)
    }

    @Test
    fun `the close button on the card clears the place`() {
        searchFor("station")
        waitForText(FAKE_STATION.name)
        compose.onNodeWithText(FAKE_STATION.name).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(string(R.string.place_card_close)).performClick()
        compose.onNodeWithText(string(R.string.home_sheet_title)).assertIsDisplayed()
        assertTrue(mapEngine.controller.overlays.isEmpty())
    }

    @Test
    fun `when search fails the message is calm and the emergency button still reaches the dialer`() {
        search.defaultAnswer = SearchOutcome.Failed(SearchError.Unavailable)
        searchFor("station")
        waitForText(string(R.string.search_error_unavailable))

        // The session is untouched: still signed in, still the app's own screens.
        assertEquals(SessionState.Ready, session.state.value)

        pressSystemBack()
        compose.onNodeWithContentDescription(string(R.string.sos_control_description)).performClick()
        compose.onNodeWithText(string(R.string.emergency_dialog_call)).performClick()
        compose.waitForIdle()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertEquals("tel:112", intent?.dataString)
    }

    @Test
    fun `the typed text and the chosen place never reach the log`() {
        ShadowLog.clear()
        searchFor("SECRETQUERY station")
        waitForText(FAKE_STATION.name)
        compose.onNodeWithText(FAKE_STATION.name).performClick()
        compose.waitForIdle()

        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }
        listOf("SECRETQUERY", FAKE_STATION.name, FAKE_STATION.label, "10.5", "20.5").forEach { secret ->
            assertFalse("log contains $secret", secret in logged)
        }
    }
}
