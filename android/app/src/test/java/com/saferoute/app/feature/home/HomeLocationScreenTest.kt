// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.testing.assertMinTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The location parts of Home: the button's states, the explanation and the notices. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class HomeLocationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()

    private var control by mutableStateOf(MyLocationControl.Off)
    private var stale by mutableStateOf(false)
    private var approximate by mutableStateOf(false)
    private var disclosure by mutableStateOf(false)
    private var notice by mutableStateOf<LocationNotice?>(null)

    private fun string(id: Int): String = context.getString(id)

    @Composable
    private fun Home(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                HomeScreen(
                    emergencyDialog = EmergencyDialogState.Hidden,
                    onSearchClick = { events += "search" },
                    onSettingsClick = { events += "settings" },
                    onEmergencyClick = { events += "emergency" },
                    onCallEmergency = {},
                    onDismissEmergencyDialog = {},
                    myLocation = control,
                    locationStale = stale,
                    locationApproximate = approximate,
                    onMyLocationClick = { events += "my-location" },
                    showLocationDisclosure = disclosure,
                    onLocationDisclosureContinue = { events += "continue" },
                    onLocationDisclosureNotNow = { events += "not-now" },
                    locationNotice = notice,
                    onLocationNoticeAction = { events += "action:$it" },
                    onLocationNoticeDismiss = { events += "dismiss" },
                )
            }
        }
    }

    private fun button(description: Int) = compose.onNodeWithContentDescription(string(description))
    private fun emergencyButton() = compose.onNodeWithContentDescription(string(R.string.sos_control_description))

    @Test
    fun `the button is never disabled and names each state`() {
        compose.setContent { Home() }
        val descriptions = mapOf(
            MyLocationControl.Off to R.string.my_location_off,
            MyLocationControl.Searching to R.string.my_location_searching,
            MyLocationControl.Located to R.string.my_location_located,
            MyLocationControl.Following to R.string.my_location_following,
            MyLocationControl.Unavailable to R.string.my_location_unavailable,
        )
        assertEquals(5, descriptions.values.map(::string).toSet().size)

        descriptions.forEach { (state, description) ->
            control = state
            events.clear()
            button(description).assertIsDisplayed().assertIsEnabled().assertMinTouchTarget().performClick()
            assertEquals("$state", listOf("my-location"), events)
        }
    }

    @Test
    fun `approximate and stale are said in words, not only shown by colour`() {
        compose.setContent { Home() }
        control = MyLocationControl.Located

        approximate = true
        button(R.string.my_location_located_approximate).assertIsDisplayed()

        // An old position is described as old, whichever kind it was.
        stale = true
        button(R.string.my_location_located_stale).assertIsDisplayed()
        assertTrue(string(R.string.my_location_located_stale) != string(R.string.my_location_located))
    }

    @Test
    fun `the explanation says what, why, what not and how to stop, with two plain choices`() {
        compose.setContent { Home() }
        compose.onNodeWithText(string(R.string.location_disclosure_title)).assertDoesNotExist()

        disclosure = true
        listOf(
            R.string.location_disclosure_title,
            R.string.location_disclosure_what,
            R.string.location_disclosure_why,
            R.string.location_disclosure_not,
            R.string.location_disclosure_stop,
        ).forEach { compose.onNodeWithText(string(it)).assertIsDisplayed() }

        compose.onNodeWithText(string(R.string.location_disclosure_not_now)).assertMinTouchTarget().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_continue)).assertMinTouchTarget().performClick()
        assertEquals(listOf("not-now", "continue"), events)
    }

    @Test
    fun `the explanation makes the promises the app keeps`() {
        // The wording is a draft for the lawyer, but these statements must stay in it.
        assertTrue(string(R.string.location_disclosure_what).contains("while the app is open"))
        assertTrue(string(R.string.location_disclosure_not).contains("background"))
        assertTrue(string(R.string.location_disclosure_not).contains("nothing is sent"))
        assertTrue(string(R.string.location_disclosure_stop).contains("Settings"))
    }

    @Test
    fun `at 200 percent font the explanation can be read to the end and both buttons work`() {
        disclosure = true
        compose.setContent { Home(fontScale = 2f) }

        compose.onNodeWithText(string(R.string.location_disclosure_stop)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.location_disclosure_continue)).assertIsDisplayed().performClick()
        compose.onNodeWithText(string(R.string.location_disclosure_not_now)).assertIsDisplayed().performClick()
        assertEquals(listOf("continue", "not-now"), events)
    }

    @Test
    fun `each notice has its message, at most one action, and can be closed`() {
        compose.setContent { Home() }
        val expected = listOf(
            Triple(LocationNotice.DeniedOnce, R.string.location_notice_denied_body, null),
            Triple(LocationNotice.DeniedPermanently, R.string.location_notice_settings_body, R.string.location_notice_open_settings),
            Triple(LocationNotice.ServicesOff, R.string.location_notice_off_body, R.string.location_notice_turn_on),
            Triple(LocationNotice.PlayServicesUnavailable, R.string.location_notice_no_play_body, null),
            Triple(LocationNotice.Approximate, R.string.location_notice_approximate_body, R.string.location_notice_use_precise),
            Triple(LocationNotice.ApproximateOnly, R.string.location_notice_approximate_body, null),
            Triple(LocationNotice.NoFix, R.string.location_notice_no_fix_body, null),
        )
        val actions = listOf(
            R.string.location_notice_open_settings,
            R.string.location_notice_turn_on,
            R.string.location_notice_use_precise,
        )

        expected.forEach { (current, body, action) ->
            notice = current
            events.clear()
            compose.onNodeWithText(string(body)).assertIsDisplayed()
            actions.filter { it != action }.forEach { compose.onNodeWithText(string(it)).assertDoesNotExist() }
            action?.let { compose.onNodeWithText(string(it)).assertMinTouchTarget().performClick() }
            compose.onNodeWithText(string(R.string.location_notice_close)).assertMinTouchTarget().performClick()
            assertEquals("$current", listOfNotNull(action?.let { "action:$current" }, "dismiss"), events)
        }

        notice = null
        compose.onNodeWithText(string(R.string.location_notice_close)).assertDoesNotExist()
    }

    @Test
    fun `no location problem blocks the emergency button, the search pill or the sheet`() {
        compose.setContent { Home() }

        LocationNotice.entries.forEach { current ->
            notice = current
            control = MyLocationControl.Unavailable
            events.clear()

            emergencyButton().assertIsDisplayed().assertMinTouchTarget().performClick()
            compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription(string(R.string.sheet_handle_description)).assertIsDisplayed()
            assertEquals("$current", listOf("emergency", "search"), events)
        }
    }

    @Test
    fun `at 200 percent font a notice leaves the emergency button and my location usable`() {
        notice = LocationNotice.DeniedPermanently
        control = MyLocationControl.Off
        compose.setContent { Home(fontScale = 2f) }

        compose.onNodeWithText(string(R.string.location_notice_open_settings)).assertIsDisplayed()
        emergencyButton().assertIsDisplayed().assertMinTouchTarget().performClick()
        button(R.string.my_location_off).assertIsDisplayed().assertMinTouchTarget().performClick()
        assertEquals(listOf("emergency", "my-location"), events)
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `the explanation and the notices are in bengali when the language is bengali`() {
        disclosure = true
        notice = LocationNotice.Approximate
        compose.setContent { Home() }

        // Literal expected values: this must fail if the app fell back to English.
        compose.onNodeWithText("মানচিত্রে আপনার অবস্থান দেখাবেন?").assertIsDisplayed()
        compose.onNodeWithText("এখন নয়").assertIsDisplayed()
        compose.onNodeWithText("আনুমানিক অবস্থান").assertExists()
    }
}
