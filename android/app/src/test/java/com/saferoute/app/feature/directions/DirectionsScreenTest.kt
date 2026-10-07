// SPDX-License-Identifier: AGPL-3.0-only
package com.saferoute.app.feature.directions

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saferoute.app.R
import com.saferoute.app.core.designsystem.component.SheetDetent
import com.saferoute.app.core.designsystem.component.rememberSafeRouteSheetState
import com.saferoute.app.core.designsystem.theme.SafeRouteTheme
import com.saferoute.app.core.map.LatLng
import com.saferoute.app.core.map.SelectedPlace
import com.saferoute.app.feature.home.EmergencyDialogState
import com.saferoute.app.feature.home.HomeScreen
import com.saferoute.app.testing.assertMinTouchTarget
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * The directions panel inside Home's sheet: what each state shows, that the emergency button
 * and the search pill stay usable in all of them, and that a route card says a time, a
 * distance and "Fastest" and nothing that could be read as a statement about safety.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class DirectionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val place = SelectedPlace("Main Station", "Station Road, Example District", LatLng(10.5, 20.5))
    private val results = DirectionsStatus.Results(listOf(FAKE_ROUTE_FAST, FAKE_ROUTE_OTHER), FAKE_ROUTE_FAST.id, FAKE_ROUTE_CREDIT)
    private val events = mutableListOf<String>()
    private var directions: DirectionsUiState by mutableStateOf(DirectionsUiState.Closed)

    private fun open(status: DirectionsStatus, mode: TravelMode = TravelMode.Walking) =
        DirectionsUiState.Open(place, mode, status)

    @Composable
    private fun Home(fontScale: Float = 1f, detent: SheetDetent = SheetDetent.Half) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SafeRouteTheme {
                HomeScreen(
                    emergencyDialog = EmergencyDialogState.Hidden,
                    onSearchClick = { events += "search" },
                    onSettingsClick = {},
                    onEmergencyClick = { events += "emergency" },
                    onCallEmergency = {},
                    onDismissEmergencyDialog = {},
                    // Half open by default, as the sheet is when there is something to read in
                    // it. The sheet is as tall as the screen and slides up: what does not fit in
                    // the visible half is reached by pulling it up (or tapping its handle).
                    sheetState = rememberSafeRouteSheetState(detent),
                    onMyLocationClick = { events += "my-location" },
                    selectedPlace = place,
                    directions = directions,
                    directionsActions = DirectionsActions(
                        onOpen = { events += "open" },
                        onIntroContinue = { events += "continue" },
                        onModeChange = { events += "mode:$it" },
                        onRouteSelect = { events += "select:$it" },
                        onRetry = { events += "retry" },
                        onClose = { events += "close" },
                    ),
                )
            }
        }
    }

    @Test
    fun `with the sheet half open the destination, the toggle and the fastest route are visible`() {
        directions = open(results)
        compose.setContent { Home() }
        compose.onNodeWithText(string(R.string.route_title_to, "Main Station")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_mode_walking)).assertIsDisplayed()
        compose.onNodeWithText("26 min").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_fastest)).assertIsDisplayed()
    }

    @Test
    fun `route cards show duration, distance, Fastest on the first, the selection and the credit`() {
        directions = open(results)
        compose.setContent { Home(detent = SheetDetent.Full) }

        compose.onNodeWithText(string(R.string.route_title_to, "Main Station")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_from_my_location)).assertIsDisplayed()
        // 1560 s is 26 minutes; 2100 m is 2.1 km. 1740 s is 29 minutes; 2440 m rounds to 2.4 km.
        compose.onNodeWithText("26 min").assertIsDisplayed()
        compose.onNodeWithText("2.1 km").assertIsDisplayed()
        compose.onNodeWithText("29 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2.4 km").assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.route_fastest)).assertCountEquals(1)
        compose.onNodeWithText(FAKE_ROUTE_CREDIT).performScrollTo().assertIsDisplayed()

        compose.onNode(hasText("26 min")).assertIsSelected()
        compose.onNode(hasText("29 min")).assertIsNotSelected().assertMinTouchTarget().performClick()
        assertEquals(listOf("select:${FAKE_ROUTE_OTHER.id}"), events)
    }

    @Test
    fun `the route screen makes no statement about safety, risk or traffic`() {
        directions = open(results)
        compose.setContent { Home() }
        val banned = listOf("safe", "risk", "danger", "secure", "traffic", "score", "crime")
        banned.forEach { word ->
            // SafeRoute's own name is the one place "safe" may appear; it is not on this screen.
            compose.onAllNodesWithText(word, substring = true, ignoreCase = true).assertCountEquals(0)
        }

        // The same for every string of the feature, in both languages, shown or not.
        val bannedBengali = listOf("নিরাপদ", "ঝুঁকি", "বিপদ", "বিপজ্জনক", "যানজট", "অপরাধ")
        val english = routeStrings("src/main/res/values/strings.xml")
        val bengali = routeStrings("src/main/res/values-bn/strings.xml")
        assertTrue(english.size >= 30)
        assertEquals(english.keys, bengali.keys)
        english.forEach { (key, text) ->
            val plain = text.replace("SafeRoute", "").lowercase(Locale.ROOT)
            banned.forEach { assertTrue("$key says \"$it\"", it !in plain) }
        }
        bengali.forEach { (key, text) ->
            bannedBengali.forEach { assertTrue("$key says \"$it\"", it !in text) }
        }
    }

    @Test
    fun `the mode toggle shows the choice and reports the other one`() {
        directions = open(results, mode = TravelMode.Driving)
        compose.setContent { Home() }
        compose.onNodeWithText(string(R.string.route_mode_driving)).assertIsSelected()
        compose.onNodeWithText(string(R.string.route_mode_walking))
            .assertIsNotSelected()
            .assertMinTouchTarget()
            .performClick()
        compose.onNodeWithContentDescription(string(R.string.route_close)).assertMinTouchTarget().performClick()
        assertEquals(listOf("mode:Walking", "close"), events)
    }

    @Test
    fun `every state has its own words, and the emergency button and the search pill work in all of them`() {
        val retry = string(R.string.route_try_again)
        val states: List<Triple<DirectionsStatus, String, Boolean>> = listOf(
            Triple(DirectionsStatus.Loading(), string(R.string.route_loading), false),
            Triple(DirectionsStatus.Loading(starting = true), string(R.string.route_starting), false),
            Triple(DirectionsStatus.Failed(RouteError.OutsideCovered), string(R.string.route_error_outside), false),
            Triple(DirectionsStatus.Failed(RouteError.NoRoute), string(R.string.route_error_no_route), false),
            Triple(DirectionsStatus.Failed(RouteError.NotRoutable), string(R.string.route_error_not_routable), false),
            Triple(DirectionsStatus.Failed(RouteError.TooLong), string(R.string.route_error_too_long), false),
            Triple(DirectionsStatus.Failed(RouteError.RateLimited(null)), string(R.string.route_error_rate_limited), true),
            Triple(
                DirectionsStatus.Failed(RouteError.RateLimited(12)),
                context.resources.getQuantityString(R.plurals.route_error_rate_limited_seconds, 12, 12),
                true,
            ),
            Triple(
                DirectionsStatus.Failed(RouteError.Starting(10), stillStarting = true),
                string(R.string.route_error_still_starting),
                true,
            ),
            Triple(DirectionsStatus.Failed(RouteError.Unavailable), string(R.string.route_error_unavailable), true),
            Triple(DirectionsStatus.Failed(RouteError.Offline), string(R.string.route_error_offline), true),
            Triple(DirectionsStatus.NeedsOrigin(OriginProblem.Searching), string(R.string.route_origin_searching), false),
        )
        compose.setContent { Home() }
        states.forEachIndexed { index, (status, text, canRetry) ->
            directions = open(status)
            compose.waitForIdle()
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onAllNodesWithText(retry).assertCountEquals(if (canRetry) 1 else 0)

            compose.onNodeWithText(string(R.string.emergency_button_label)).assertIsDisplayed().performClick()
            compose.onNodeWithText(string(R.string.search_hint)).assertIsDisplayed().performClick()
            assertEquals("state $index", listOf("emergency", "search"), events.takeLast(2))
        }
        assertEquals(states.size * 2, events.size)
    }

    @Test
    fun `Try again and Use my location report their taps`() {
        directions = open(DirectionsStatus.Failed(RouteError.Starting(10), stillStarting = true))
        compose.setContent { Home() }
        compose.onNodeWithText(string(R.string.route_try_again)).assertMinTouchTarget().performClick()

        directions = open(DirectionsStatus.NeedsOrigin(OriginProblem.NoPermission))
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_origin_no_permission)).assertIsDisplayed()
        // The same permission flow as the map's own button: one way in, never a second dialog.
        compose.onNodeWithText(string(R.string.route_use_my_location)).assertMinTouchTarget().performClick()

        directions = open(DirectionsStatus.NeedsOrigin(OriginProblem.Unavailable))
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_origin_unavailable)).assertIsDisplayed()
        assertEquals(listOf("retry", "my-location"), events)
    }

    @Test
    fun `the one-time note says what is sent and that it is not stored, and both buttons work`() {
        directions = DirectionsUiState.Intro(place)
        compose.setContent { Home() }
        compose.onNodeWithText(string(R.string.route_intro_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_intro_sent)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.route_intro_not_stored)).assertIsDisplayed()
        assertTrue("sent to SafeRoute" in string(R.string.route_intro_sent))
        assertTrue("not stored" in string(R.string.route_intro_not_stored))
        // The place card is still behind the note: nothing was opened yet.
        compose.onNodeWithText(string(R.string.route_intro_not_now)).performClick()
        compose.onNodeWithText(string(R.string.route_intro_continue)).performClick()
        assertEquals(listOf("close", "continue"), events)
    }

    @Test
    fun `at double font size every card, the credit and Try again can be scrolled to`() {
        directions = open(results)
        compose.setContent { Home(fontScale = 2f, detent = SheetDetent.Full) }
        compose.onNodeWithText("26 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("29 min").performScrollTo().assertIsDisplayed().assertMinTouchTarget()
        compose.onNodeWithText(FAKE_ROUTE_CREDIT).performScrollTo().assertIsDisplayed()

        directions = open(DirectionsStatus.Failed(RouteError.Offline))
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_try_again)).performScrollTo().assertIsDisplayed().assertMinTouchTarget()
    }

    @Test
    @Config(qualifiers = "bn-w360dp-h640dp")
    fun `in Bengali the cards, the toggle and the messages are translated`() {
        directions = open(results)
        compose.setContent { Home() }
        compose.onNodeWithText("দ্রুততম").assertIsDisplayed()
        compose.onNodeWithText("হেঁটে").assertIsDisplayed()
        compose.onAllNodesWithText("কিমি", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("মিনিট", substring = true).assertCountEquals(2)

        directions = open(DirectionsStatus.Loading(starting = true))
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.route_starting)).assertIsDisplayed()
        assertTrue("চালু হচ্ছে" in string(R.string.route_starting))
    }

    @Test
    fun `durations round up to whole minutes and distances are short to read`() {
        assertEquals(1, routeMinutes(1))
        assertEquals(1, routeMinutes(60))
        assertEquals(2, routeMinutes(61))
        assertEquals(60, routeMinutes(3600))
        assertEquals(10, routeRoundedMeters(3))
        assertEquals(440, routeRoundedMeters(437))
        assertEquals("1.0", routeKilometres(1000, Locale.ENGLISH))
        assertEquals("2.1", routeKilometres(2149, Locale.ENGLISH))
        assertEquals("572.1", routeKilometres(572_100, Locale.ENGLISH))
        // A decimal comma where the language uses one.
        assertEquals("2,1", routeKilometres(2100, Locale.GERMAN))
    }

    @Test
    fun `an hour or more is shown as hours and minutes`() {
        directions = open(results.copy(routes = listOf(FAKE_ROUTE_FAST.copy(durationSeconds = 28_080, distanceMeters = 572_100))))
        compose.setContent { Home() }
        compose.onNodeWithText("7 h 48 min").assertIsDisplayed()
        compose.onNodeWithText("572.1 km").assertIsDisplayed()
    }

    /** "name" → text for every string and plural item of the feature. */
    private fun routeStrings(path: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        val result = linkedMapOf<String, String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (name.startsWith("route_") || name == "place_card_directions") result[name] = element.textContent
        }
        return result
    }
}
